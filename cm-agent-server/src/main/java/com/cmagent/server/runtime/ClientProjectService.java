package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ClientProject;
import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.ClientProjectOperation;
import com.cmagent.core.domain.ClientProjectTask;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.domain.LocalProjectPageRequest;
import com.cmagent.core.repository.ClientProjectOperationRepository;
import com.cmagent.core.repository.ClientProjectRepository;
import com.cmagent.core.repository.ClientProjectTaskRepository;
import com.cmagent.core.repository.LocalClientDeviceRepository;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.ClientProjectProperties;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 已配对设备项目的浏览器登记、设备任务领取和终态编排。
 *
 * <p>浏览器和设备身份均来自各自认证边界。绝对路径、设备和根不能由浏览器覆盖；
 * 任务正文只保存认证加密值，文件 I/O 不在数据库事务内执行。</p>
 */
public final class ClientProjectService {
    private static final int MAX_RESULT_BYTES = 1_048_576;
    private final ClientProjectRepository projects;
    private final ClientProjectOperationRepository operations;
    private final ClientProjectTaskRepository tasks;
    private final LocalClientDeviceRepository devices;
    private final PermissionEvaluator permissions;
    private final AuditAppender audit;
    private final ErrorDiagnosticLogger diagnostics;
    private final ModelCredentialCipher cipher;
    private final SensitiveDataRedactor redactor;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final ClientProjectProperties properties;
    private final Clock clock;
    /** 命令服务由独立配置提供；文件纵切未启用命令时保持为空。 */
    private ClientProjectCommandService clientProjectCommands;

    @org.springframework.beans.factory.annotation.Autowired
    void configureClientProjectCommands(
            org.springframework.beans.factory.ObjectProvider<ClientProjectCommandService> provider) {
        clientProjectCommands = provider.getIfAvailable();
    }

    public ClientProjectService(ClientProjectRepository projects,
            ClientProjectOperationRepository operations, ClientProjectTaskRepository tasks,
            LocalClientDeviceRepository devices, PermissionEvaluator permissions, AuditAppender audit,
            ErrorDiagnosticLogger diagnostics, ModelCredentialCipher cipher, SensitiveDataRedactor redactor,
            ObjectMapper json, TransactionTemplate transaction, ClientProjectProperties properties,
            Clock clock) {
        this.projects = Objects.requireNonNull(projects);
        this.operations = Objects.requireNonNull(operations);
        this.tasks = Objects.requireNonNull(tasks);
        this.devices = Objects.requireNonNull(devices);
        this.permissions = Objects.requireNonNull(permissions);
        this.audit = Objects.requireNonNull(audit);
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.cipher = Objects.requireNonNull(cipher);
        this.redactor = Objects.requireNonNull(redactor);
        this.json = Objects.requireNonNull(json);
        this.transaction = Objects.requireNonNull(transaction);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 浏览器为指定设备登记根目录选择任务。 */
    public TaskView startSelection(PrincipalRef principal, UUID requestId, UUID deviceId,
            String suggestedName) {
        authorize(principal, "project:manage", requestId);
        LocalClientDevice device = activeDevice(principal, deviceId, requestId);
        var existing = tasks.findForOwner(principal.tenantId(), principal.principalId(), requestId)
                .orElse(null);
        if (existing != null) {
            if (existing.kind() != ClientProjectTask.Kind.SELECT_ROOT
                    || !existing.deviceId().equals(device.id())) {
                throw reject(principal, requestId, "CLIENT_PROJECT_REQUEST_CONFLICT", 409,
                        "同一请求编号已用于其他设备项目任务");
            }
            return view(existing);
        }
        ObjectNode request = json.createObjectNode().put("kind", "SELECT_ROOT")
                .put("deviceName", device.displayName());
        if (suggestedName != null && !suggestedName.isBlank()) {
            request.put("suggestedName", validName(suggestedName));
        }
        Instant now = clock.instant();
        var task = new ClientProjectTask(requestId, principal.tenantId(), principal.principalId(),
                device.id(), null, null, null, null, null,
                ClientProjectTask.Kind.SELECT_ROOT, encrypt(request),
                ClientProjectTask.State.PENDING, "", "", now,
                now.plus(properties.getSelectionTtl()), null, null, 1);
        return inTransaction(() -> {
            if (!tasks.insertSelectionExclusive(task)) {
                var raced = tasks.findForOwner(principal.tenantId(), principal.principalId(), requestId)
                        .orElseThrow(() -> reject(principal, requestId,
                                "CLIENT_PROJECT_SELECTION_PENDING", 409,
                                "该设备已有待完成的文件夹选择窗口"));
                return view(raced);
            }
            record(principal, requestId, "CLIENT_PROJECT_SELECTION_REQUESTED", "PENDING");
            return view(task);
        });
    }

    /** 浏览器读取选择或只读任务的当前状态；成功结果只在原主体范围内解密。 */
    public TaskStatusView taskStatus(PrincipalRef principal, UUID taskId) {
        authorize(principal, "project:read", taskId);
        var task = tasks.findForOwner(principal.tenantId(), principal.principalId(), taskId)
                .orElseThrow(() -> reject(principal, taskId, "CLIENT_PROJECT_TASK_NOT_FOUND", 404,
                        "设备项目任务不存在或不可访问"));
        Instant now = clock.instant();
        if (task.state() == ClientProjectTask.State.PENDING && !task.expiresAt().isAfter(now)) {
            closeTask(task, ClientProjectTask.State.CANCELLED,
                    "设备项目任务在领取前过期", now);
            task = tasks.findForOwner(principal.tenantId(), principal.principalId(), taskId)
                    .orElse(task);
        } else if (task.state() == ClientProjectTask.State.DISPATCHED && !task.expiresAt().isAfter(now)) {
            closeTask(task, ClientProjectTask.State.UNKNOWN,
                    "设备项目任务在结果确认前过期", now);
            task = tasks.findForOwner(principal.tenantId(), principal.principalId(), taskId)
                    .orElse(task);
        }
        JsonNode output = null;
        if (task.state() == ClientProjectTask.State.SUCCEEDED && !task.encryptedResult().isBlank()) {
            output = decrypt(task.encryptedResult(), principal, task.id());
        }
        UUID projectId = output != null && output.path("projectId").isTextual()
                ? UUID.fromString(output.path("projectId").asText()) : task.projectId();
        ProjectView project = projectId == null ? null
                : projects.find(principal.tenantId(), principal.principalId(), projectId)
                        .map(this::projectView).orElse(null);
        return new TaskStatusView(task.id(), task.kind().name(), task.state().name(),
                task.safeError(), output, project);
    }

    /** 超过同步工具等待预算时关闭原任务；已领取任务保持 UNKNOWN，不自动重派。 */
    public void abandonTask(PrincipalRef principal, UUID taskId, String reason) {
        authorize(principal, "project:read", taskId);
        ClientProjectTask task = tasks.findForOwner(principal.tenantId(),
                principal.principalId(), taskId).orElse(null);
        if (task == null) return;
        ClientProjectTask.State state = task.state() == ClientProjectTask.State.PENDING
                ? ClientProjectTask.State.CANCELLED : ClientProjectTask.State.UNKNOWN;
        closeTask(task, state, redactor.redact(reason), clock.instant());
    }

    /** 设备签名领取一条固定项目任务并返回经认证加密保护的请求 JSON。 */
    public Optional<DeviceTaskView> claim(LocalClientDevice device) {
        if (device.state() != LocalClientDevice.State.ACTIVE) return Optional.empty();
        var task = tasks.claimNext(device.tenantId(), device.ownerId(), device.id(), clock.instant());
        if (task.isEmpty()) return Optional.empty();
        var current = task.orElseThrow();
        if (current.projectId() != null) {
            var project = projects.find(device.tenantId(), device.ownerId(), current.projectId()).orElse(null);
            if (project == null || project.state() != ClientProject.State.ACTIVE
                    || !project.deviceId().equals(device.id())) {
                try {
                    tasks.close(device.tenantId(), device.ownerId(), device.id(), current.id(),
                            current.version(), ClientProjectTask.State.CANCELLED,
                            "项目已归档、删除或设备来源已变化", clock.instant());
                } catch (RuntimeException ignored) {
                    // 任务保持 DISPATCHED 也不会返回正文；后续只能由权威状态对账。
                }
                return Optional.empty();
            }
            if (current.kind() == ClientProjectTask.Kind.APPLY_CHANGE) {
                ClientProjectOperation operation = operations.find(device.tenantId(),
                        device.ownerId(), current.operationId()).orElse(null);
                if (operation == null
                        || operation.state() != ClientProjectOperation.State.APPLYING
                        || !operation.projectId().equals(current.projectId())
                        || !operation.runId().equals(current.runId())) {
                    try {
                        tasks.close(device.tenantId(), device.ownerId(), device.id(),
                                current.id(), current.version(),
                                ClientProjectTask.State.CANCELLED,
                                "变更许可已失效或已撤销", clock.instant());
                    } catch (RuntimeException ignored) {
                        // 不返回任何变更正文；残留 DISPATCHED 只能由权威状态对账。
                    }
                    return Optional.empty();
                }
            }
            if (current.commandId() != null && clientProjectCommands != null
                    && !clientProjectCommands.claimable(current)) {
                try {
                    tasks.close(device.tenantId(), device.ownerId(), device.id(),
                            current.id(), current.version(),
                            ClientProjectTask.State.CANCELLED,
                            "命令许可或状态已失效", clock.instant());
                } catch (RuntimeException ignored) {
                    // 不返回任何命令清单；残留任务只能由权威状态对账。
                }
                return Optional.empty();
            }
        }
        try {
            record(device.tenantId(), device.ownerId(), current.id(),
                    "CLIENT_PROJECT_TASK_DISPATCH", "DISPATCHED");
            String category = switch (current.kind()) {
                case SELECT_ROOT -> "PROJECT_SELECT_ROOT";
                case PLAN_CHANGE -> "PROJECT_CHANGE_PLAN";
                case APPLY_CHANGE -> "PROJECT_CHANGE_APPLY";
                case LIST_TOOLCHAINS -> "PROJECT_LIST_TOOLCHAINS";
                case PLAN_COMMAND -> "PROJECT_COMMAND_PLAN";
                case RUN_COMMAND -> "PROJECT_COMMAND_RUN";
                case COMMAND_STATUS -> "PROJECT_COMMAND_STATUS";
                case STOP_COMMAND -> "PROJECT_COMMAND_STOP";
                default -> "PROJECT_READ_ONLY";
            };
            JsonNode request = decrypt(current.encryptedRequest(), device, current.id());
            if (current.kind() == ClientProjectTask.Kind.SELECT_ROOT) {
                if (!(request instanceof ObjectNode selection)) throw new IllegalStateException("选择任务参数无效");
                // 截止时间来自服务端持久化任务而非浏览器；客户端据此关闭窗口并拒绝迟到登记。
                selection.put("expiresAt", current.expiresAt().toString());
            }
            return Optional.of(new DeviceTaskView(current.id(), category, current.version(), request));
        } catch (RuntimeException failure) {
            try {
                tasks.close(device.tenantId(), device.ownerId(), device.id(), current.id(),
                        current.version(), ClientProjectTask.State.UNKNOWN,
                        "任务请求密文或派发审计不可用", clock.instant());
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * 设备签名回报任务结果。选择根成功时项目和任务成功在同一 Server 短事务提交。
     */
    public void complete(LocalClientDevice device, UUID taskId, byte[] body) {
        ResultPayload payload = result(body);
        var task = tasks.find(device.tenantId(), device.ownerId(), device.id(), taskId)
                .orElseThrow(() -> reject(device, taskId, "CLIENT_PROJECT_TASK_NOT_FOUND", 404,
                        "设备项目任务不存在或不属于该设备"));
        if (task.state() != ClientProjectTask.State.DISPATCHED
                || task.version() != payload.expectedVersion()) {
            throw reject(device, taskId, "CLIENT_PROJECT_TASK_STALE", 409,
                    "设备项目任务已处理或版本已变化");
        }
        Instant now = clock.instant();
        if (!task.expiresAt().isAfter(now)) {
            closeTask(task, ClientProjectTask.State.UNKNOWN,
                    "设备项目任务结果在期限后到达", now);
            throw reject(device, taskId, "CLIENT_PROJECT_TASK_EXPIRED", 409,
                    "设备项目任务已过期，结果保持未知且不会自动重试");
        }
        if (payload.state() == ClientProjectTask.State.SUCCEEDED) {
            try {
                if (task.kind() == ClientProjectTask.Kind.SELECT_ROOT) {
                    completeSelection(device, task, payload.output());
                } else if (task.kind() == ClientProjectTask.Kind.PLAN_CHANGE) {
                    completeChangePlan(device, task, payload.output(), now);
                } else if (task.kind() == ClientProjectTask.Kind.APPLY_CHANGE) {
                    completeChangeApply(device, task, payload.output(), now);
                } else if (isCommandTask(task.kind())) {
                    if (clientProjectCommands == null) {
                        throw new IllegalStateException("设备项目命令服务未装配");
                    }
                    clientProjectCommands.completeSuccess(device, task, payload.output(), now);
                } else {
                    if (payload.output() == null || payload.output().toString().length() > MAX_RESULT_BYTES) {
                        throw reject(device, taskId, "CLIENT_PROJECT_RESULT_INVALID", 400,
                                "设备项目任务结果无效或过大");
                    }
                    JsonNode safeOutput = redactResult(task.kind(), payload.output());
                    boolean saved = tasks.complete(device.tenantId(), device.ownerId(), device.id(),
                            task.id(), task.version(), ClientProjectTask.State.SUCCEEDED,
                            encrypt(safeOutput), "", now);
                    if (!saved) throw stale(device, task.id());
                    record(device.tenantId(), device.ownerId(), task.id(),
                            "CLIENT_PROJECT_TASK_COMPLETE", "SUCCEEDED");
                }
            } catch (LocalProjectAccessException failure) {
                throw failure;
            } catch (IllegalArgumentException invalid) {
                throw reject(device, taskId, "CLIENT_PROJECT_RESULT_INVALID", 400,
                        "设备项目任务结果字段无效");
            }
            return;
        }
        String safeError = redactor.redact(payload.error() == null || payload.error().isBlank()
                ? "设备项目任务失败" : payload.error());
        if (safeError.length() > 500) safeError = safeError.substring(0, 500);
        ClientProjectTask.State state = payload.state() == ClientProjectTask.State.UNKNOWN
                ? ClientProjectTask.State.UNKNOWN : ClientProjectTask.State.FAILED;
        final String finalError = safeError;
        final ClientProjectTask.State finalState = state;
        inTransaction(() -> {
            if (isCommandTask(task.kind()) && clientProjectCommands != null) {
                boolean possible = payload.output() == null
                        || payload.output().path("possibleSideEffects").asBoolean(true);
                clientProjectCommands.completeFailure(device, task, finalState, finalError,
                        possible, now);
                return Boolean.TRUE;
            }
            if (task.kind() == ClientProjectTask.Kind.APPLY_CHANGE) {
                ClientProjectOperation operation = operations.find(task.tenantId(),
                        task.ownerId(), task.operationId()).orElseThrow(
                                () -> new IllegalStateException("应用任务对应的操作不存在"));
                boolean possible = payload.output() == null
                        || payload.output().path("possibleSideEffects").asBoolean(true);
                ClientProjectOperation.State operationState = payload.state()
                        == ClientProjectTask.State.UNKNOWN || possible
                        ? ClientProjectOperation.State.UNKNOWN
                        : ClientProjectOperation.State.FAILED;
                operations.advance(task.tenantId(), task.ownerId(), operation.id(),
                        operation.version(), operationState, now).orElseThrow(
                                () -> new IllegalStateException("应用失败状态无法保存"));
            }
            if (!tasks.complete(device.tenantId(), device.ownerId(), device.id(), task.id(),
                    task.version(), finalState, "", finalError, now)) {
                throw stale(device, task.id());
            }
            return Boolean.TRUE;
        });
        record(device.tenantId(), device.ownerId(), task.id(),
                "CLIENT_PROJECT_TASK_COMPLETE", state.name());
    }

    /** 浏览器登记项目级只读任务。 */
    public TaskView startQuery(PrincipalRef principal, UUID projectId, UUID requestId,
            ClientProjectTask.Kind kind, ReadRequest request) {
        if (kind != ClientProjectTask.Kind.LIST && kind != ClientProjectTask.Kind.READ
                && kind != ClientProjectTask.Kind.SEARCH) {
            throw reject(principal, projectId, "CLIENT_PROJECT_TASK_INVALID", 400,
                    "只读查询种类无效");
        }
        if (kind == ClientProjectTask.Kind.SEARCH
                && (request.query() == null || request.query().isBlank())) {
            throw reject(principal, projectId, "CLIENT_PROJECT_TASK_INVALID", 400,
                    "设备项目搜索词不能为空");
        }
        authorize(principal, "project:read", projectId);
        ClientProject project = activeProject(principal, projectId);
        var existing = tasks.findForOwner(principal.tenantId(), principal.principalId(), requestId)
                .orElse(null);
        if (existing != null) {
            if (!Objects.equals(existing.projectId(), projectId) || existing.kind() != kind) {
                throw reject(principal, requestId, "CLIENT_PROJECT_REQUEST_CONFLICT", 409,
                        "同一请求编号已用于其他设备项目任务");
            }
            return view(existing);
        }
        ObjectNode payload = json.createObjectNode().put("kind", kind.name())
                .put("rootId", project.clientRootId().toString())
                .put("path", request.path() == null ? "" : request.path());
        if (kind == ClientProjectTask.Kind.LIST) {
            payload.put("cursor", request.cursor() == null ? "" : request.cursor());
            payload.put("limit", request.limit());
        } else if (kind == ClientProjectTask.Kind.READ) {
            payload.put("offset", request.offset());
            payload.put("maxBytes", request.maxBytes());
        } else {
            payload.put("query", request.query());
            payload.put("content", request.content());
        }
        Instant now = clock.instant();
        var task = new ClientProjectTask(requestId, principal.tenantId(), principal.principalId(),
                project.deviceId(), project.id(), null, null, null, null, kind, encrypt(payload),
                ClientProjectTask.State.PENDING, "", "", now, now.plus(properties.getQueryTtl()),
                null, null, 1);
        return inTransaction(() -> {
            if (!tasks.insert(task)) {
                return view(tasks.findForOwner(principal.tenantId(), principal.principalId(), requestId)
                        .orElseThrow(() -> new IllegalStateException("项目任务冲突但不可读取")));
            }
            record(principal, requestId, "CLIENT_PROJECT_QUERY_REQUESTED", kind.name());
            return view(task);
        });
    }

    /**
     * 让目标设备读取当前文件并形成零副作用变更计划。
     *
     * @param principal 原认证主体
     * @param binding 已固定到当前 Run 的设备项目来源
     * @param changes 模型提交的固定创建、替换或删除动作
     * @param undoOf 条件撤销原操作；普通计划为空
     * @return 可被浏览器或模型轮询的计划任务
     */
    public TaskView startChangePlan(PrincipalRef principal, ClientProjectBinding binding,
            JsonNode changes, UUID undoOf) {
        authorize(principal, "project:write", binding.projectId());
        ClientProject project = activeBoundProject(principal, binding);
        activeDevice(principal, binding.deviceId(), project.id());
        if (operations.hasUncertain(principal.tenantId(), principal.principalId(), project.id())) {
            throw reject(principal, project.id(), "CLIENT_PROJECT_OPERATION_UNCERTAIN", 409,
                    "项目仍有执行中或结果未知的变更，请先核对");
        }
        if (clientProjectCommands != null && clientProjectCommands.hasUnsettled(
                principal.tenantId(), principal.principalId(), project.id())) {
            throw reject(principal, project.id(), "CLIENT_PROJECT_COMMAND_UNCERTAIN", 409,
                    "项目仍有执行中或结果未知的命令，请先停止并核对");
        }
        if (changes == null || !changes.isArray() || changes.isEmpty()
                || changes.size() > 20) {
            throw reject(principal, project.id(), "CLIENT_PROJECT_CHANGE_INVALID", 400,
                    "变更计划必须包含 1 至 20 个动作");
        }
        UUID requestId = UUID.randomUUID();
        ObjectNode request = json.createObjectNode().put("kind", "PLAN_CHANGE")
                .put("rootId", project.clientRootId().toString());
        if (undoOf != null) request.put("undoOf", undoOf.toString());
        request.set("changes", changes);
        ClientProjectTask task = new ClientProjectTask(requestId, principal.tenantId(),
                principal.principalId(), project.deviceId(), project.id(), binding.runId(),
                binding.agentId(), binding.conversationId(), null,
                ClientProjectTask.Kind.PLAN_CHANGE, encrypt(request),
                ClientProjectTask.State.PENDING, "", "", clock.instant(),
                clock.instant().plus(properties.getQueryTtl()), null, null, 1);
        return inTransaction(() -> {
            if (!tasks.insert(task)) throw new IllegalStateException("变更计划任务无法登记");
            record(principal, task.id(), "CLIENT_PROJECT_CHANGE_PLAN", "PENDING");
            return view(task);
        });
    }

    /**
     * 在原提案版本、摘要和脱敏完整性仍匹配时取得一次应用许可并登记设备任务。
     *
     * <p>通用 HIGH 审批必须由调用方在进入本方法前完成；本方法不接受请求体中的 approved 布尔值。</p>
     *
     * @return 唯一应用任务
     */
    public TaskView startApply(PrincipalRef principal, ClientProjectBinding binding,
            UUID operationId, long expectedVersion, String digest) {
        authorize(principal, "project:write", binding.projectId());
        return inTransaction(() -> {
            ClientProject project = projects.lock(principal.tenantId(),
                    principal.principalId(), binding.projectId()).orElseThrow(
                            () -> reject(principal, binding.projectId(),
                                    "CLIENT_PROJECT_NOT_FOUND", 404, "设备项目不存在或不可访问"));
            if (project.state() != ClientProject.State.ACTIVE) {
                throw reject(principal, project.id(), "CLIENT_PROJECT_ARCHIVED", 409,
                        "设备项目已归档");
            }
            activeDevice(principal, binding.deviceId(), project.id());
            ClientProjectOperation operation = operations.lock(principal.tenantId(),
                    principal.principalId(), operationId).orElseThrow(
                            () -> reject(principal, operationId,
                                    "CLIENT_PROJECT_OPERATION_NOT_FOUND", 404,
                                    "设备项目操作不存在或不可访问"));
            if (!operation.projectId().equals(project.id())
                    || !operation.agentId().equals(binding.agentId())
                    || !operation.conversationId().equals(binding.conversationId())
                    || !operation.runId().equals(binding.runId())
                    || operation.state() != ClientProjectOperation.State.PROPOSED
                    || operation.version() != expectedVersion
                    || !operation.planDigest().equals(digest)) {
                throw reject(principal, operationId, "CLIENT_PROJECT_PROPOSAL_CHANGED", 409,
                        "变更提案版本、摘要或状态已变化，请重新核对");
            }
            if (!operation.displayExact()) {
                throw reject(principal, operationId, "CLIENT_PROJECT_PREVIEW_REDACTED", 409,
                        "脱敏预览掩盖了实际差异，禁止批准；请缩小变更范围");
            }
            if (operations.hasUncertain(principal.tenantId(), principal.principalId(), project.id())) {
                throw reject(principal, project.id(), "CLIENT_PROJECT_OPERATION_UNCERTAIN", 409,
                        "项目仍有执行中或结果未知的变更，请先核对");
            }
            if (clientProjectCommands != null && clientProjectCommands.hasUnsettled(
                    principal.tenantId(), principal.principalId(), project.id())) {
                throw reject(principal, project.id(), "CLIENT_PROJECT_COMMAND_UNCERTAIN", 409,
                        "项目仍有执行中或结果未知的命令，请先停止并核对");
            }
            JsonNode plan = decryptJson(operation.encryptedPlan(), principal, operation.id());
            ObjectNode request = ((ObjectNode) plan.deepCopy()).put("planDigest",
                    operation.planDigest()).put("operationId", operation.id().toString());
            UUID taskId = UUID.randomUUID();
            ClientProjectTask task = new ClientProjectTask(taskId, principal.tenantId(),
                    principal.principalId(), project.deviceId(), project.id(), binding.runId(),
                    binding.agentId(), binding.conversationId(), operation.id(),
                    ClientProjectTask.Kind.APPLY_CHANGE, encrypt(request),
                    ClientProjectTask.State.PENDING, "", "", clock.instant(),
                    clock.instant().plus(properties.getQueryTtl()), null, null, 1);
            if (!tasks.insert(task)) throw new IllegalStateException("变更应用任务无法登记");
            operations.advance(principal.tenantId(), principal.principalId(), operation.id(),
                    operation.version(), ClientProjectOperation.State.APPLYING,
                    clock.instant()).orElseThrow(() ->
                            new IllegalStateException("变更应用许可条件已失效"));
            record(principal, task.id(), "CLIENT_PROJECT_CHANGE_APPLY", "PENDING");
            return view(task);
        });
    }

    /** 为成功变更生成新的条件撤销计划；原始正文仍在 Server 加密计划中。 */
    public TaskView startUndoPlan(PrincipalRef principal, ClientProjectBinding binding,
            UUID originalOperationId) {
        authorize(principal, "project:write", binding.projectId());
        ClientProjectOperation original = operations.find(principal.tenantId(),
                principal.principalId(), originalOperationId).orElseThrow(
                        () -> reject(principal, originalOperationId,
                                "CLIENT_PROJECT_OPERATION_NOT_FOUND", 404,
                                "设备项目操作不存在或不可访问"));
        if (!original.projectId().equals(binding.projectId())
                || !original.runId().equals(binding.runId())
                || original.state() != ClientProjectOperation.State.SUCCEEDED) {
            throw reject(principal, originalOperationId, "CLIENT_PROJECT_UNDO_UNAVAILABLE", 409,
                    "仅当前 Run 已成功的变更可以生成条件撤销");
        }
        JsonNode plan = decryptJson(original.encryptedPlan(), principal, original.id());
        ArrayNode reversed = json.createArrayNode();
        for (JsonNode change : plan.path("changes")) {
            String kind = change.path("kind").asText();
            ObjectNode item = reversed.addObject().put("path", change.path("path").asText());
            switch (kind) {
                case "CREATE_FILE" -> item.put("kind", "DELETE_FILE")
                        .put("expectedSha256", change.path("afterSha256").asText());
                case "REPLACE_FILE" -> item.put("kind", "REPLACE_FILE")
                        .put("expectedSha256", change.path("afterSha256").asText())
                        .put("content", change.path("before").asText(""));
                case "DELETE_FILE" -> item.put("kind", "CREATE_FILE")
                        .put("content", change.path("before").asText(""));
                case "MOVE_FILE" -> item.put("kind", "MOVE_FILE")
                        .put("path", change.path("target").asText())
                        .put("target", change.path("path").asText())
                        .put("expectedSha256", change.path("afterSha256").asText());
                case "CREATE_DIRECTORY" -> item.put("kind", "DELETE_EMPTY_DIRECTORY");
                case "DELETE_EMPTY_DIRECTORY" -> item.put("kind", "CREATE_DIRECTORY");
                default -> throw reject(principal, originalOperationId,
                        "CLIENT_PROJECT_UNDO_UNSUPPORTED", 409,
                        "原变更包含不能安全撤销的动作");
            }
        }
        return startChangePlan(principal, binding, reversed, originalOperationId);
    }

    /** 原主体读取脱敏精确预览；原始正文和文件身份不进入浏览器响应。 */
    public ChangePreview changePreview(PrincipalRef principal, UUID projectId, UUID operationId) {
        ClientProjectOperation operation = operations.find(principal.tenantId(),
                principal.principalId(), operationId).orElseThrow(
                        () -> reject(principal, operationId,
                                "CLIENT_PROJECT_OPERATION_NOT_FOUND", 404,
                                "设备项目操作不存在或不可访问"));
        if (!operation.projectId().equals(projectId)) {
            throw reject(principal, operationId, "CLIENT_PROJECT_OPERATION_NOT_FOUND", 404,
                    "设备项目操作不存在或不可访问");
        }
        authorize(principal, "project:write", operation.projectId());
        JsonNode plan = decryptJson(operation.encryptedPlan(), principal, operation.id());
        List<PreviewChange> changes = new ArrayList<>();
        for (JsonNode change : plan.path("changes")) {
            changes.add(new PreviewChange(change.path("kind").asText(),
                    change.path("path").asText(), nullableText(change, "target"),
                    redact(nullableText(change, "before")),
                    redact(nullableText(change, "after")),
                    nullableText(change, "beforeSha256"),
                    nullableText(change, "afterSha256")));
        }
        return new ChangePreview(operation.id(), operation.state().name(),
                operation.version(), operation.planDigest(), operation.displayDigest(),
                operation.displayExact(), operation.createdAt(), List.copyOf(changes));
    }

    /** @return 原主体可见的安全操作状态 */
    public OperationView operation(PrincipalRef principal, UUID projectId, UUID operationId) {
        ClientProjectOperation operation = operations.find(principal.tenantId(),
                principal.principalId(), operationId).orElseThrow(
                        () -> reject(principal, operationId,
                                "CLIENT_PROJECT_OPERATION_NOT_FOUND", 404,
                                "设备项目操作不存在或不可访问"));
        if (!operation.projectId().equals(projectId)) {
            throw reject(principal, operationId, "CLIENT_PROJECT_OPERATION_NOT_FOUND", 404,
                    "设备项目操作不存在或不可访问");
        }
        authorize(principal, "project:read", operation.projectId());
        return new OperationView(operation.id(), operation.projectId(), operation.runId(),
                operation.state().name(), operation.version(), operation.planDigest(),
                operation.displayDigest(), operation.displayExact(), operation.createdAt(),
                operation.updatedAt());
    }

    public List<ProjectView> list(PrincipalRef principal, LocalProjectPageRequest page,
            boolean includeArchived) {
        authorize(principal, "project:read", null);
        return projects.list(principal.tenantId(), principal.principalId(), page, includeArchived)
                .stream().map(this::projectView).toList();
    }

    public ProjectView project(PrincipalRef principal, UUID projectId) {
        authorize(principal, "project:read", projectId);
        return projectView(projects.find(principal.tenantId(), principal.principalId(), projectId)
                .orElseThrow(() -> reject(principal, projectId,
                        "CLIENT_PROJECT_NOT_FOUND", 404, "设备项目不存在或不可访问")));
    }

    public ProjectView rename(PrincipalRef principal, UUID projectId, long expectedVersion,
            String name) {
        authorize(principal, "project:manage", projectId);
        String valid = validName(name);
        if (!projects.rename(principal.tenantId(), principal.principalId(), projectId,
                expectedVersion, valid, clock.instant())) {
            throw reject(principal, projectId, "CLIENT_PROJECT_VERSION_CONFLICT", 409,
                    "设备项目名称已变化，请刷新后重试");
        }
        record(principal, projectId, "CLIENT_PROJECT_RENAME", "SUCCEEDED");
        return project(principal, projectId);
    }

    public ProjectView archive(PrincipalRef principal, UUID projectId, long expectedVersion) {
        authorize(principal, "project:manage", projectId);
        if (tasks.hasUnresolvedForProject(principal.tenantId(), principal.principalId(), projectId)
                || operations.hasUncertain(principal.tenantId(), principal.principalId(), projectId)
                || clientProjectCommands != null && clientProjectCommands.hasUnsettled(
                    principal.tenantId(), principal.principalId(), projectId)) {
            throw reject(principal, projectId, "CLIENT_PROJECT_TASK_PENDING", 409,
                    "项目仍有待领取、执行中或结果未知任务，请先核对");
        }
        if (!projects.archive(principal.tenantId(), principal.principalId(), projectId,
                expectedVersion, clock.instant())) {
            throw reject(principal, projectId, "CLIENT_PROJECT_VERSION_CONFLICT", 409,
                    "设备项目状态已变化，请刷新后重试");
        }
        record(principal, projectId, "CLIENT_PROJECT_ARCHIVE", "SUCCEEDED");
        return project(principal, projectId);
    }

    /** 浏览器来源边界拒绝时统一写安全审计并返回同号错误。 */
    public LocalProjectAccessException requestRejected(PrincipalRef principal, UUID resourceId) {
        return reject(principal, resourceId, "CLIENT_PROJECT_REQUEST_REQUIRED", 403,
                "请从已配置的控制台来源执行设备项目操作");
    }

    private void completeChangePlan(LocalClientDevice device, ClientProjectTask task,
            JsonNode output, Instant now) {
        List<PlanChange> changes = planChanges(output);
        String digest = planDigest(changes);
        if (!digest.equals(output.path("planDigest").asText())) {
            throw reject(device, task.id(), "CLIENT_PROJECT_PLAN_INVALID", 400,
                    "设备变更计划摘要不一致");
        }
        boolean exact = true;
        List<PreviewChange> preview = new ArrayList<>();
        for (PlanChange change : changes) {
            String displayBefore = redact(change.before());
            String displayAfter = redact(change.after());
            if (!Objects.equals(change.before(), change.after())
                    && (displayBefore.contains("<已脱敏>") || displayAfter.contains("<已脱敏>")
                        || Objects.equals(displayBefore, displayAfter))) {
                exact = false;
            }
            preview.add(new PreviewChange(change.kind(), change.path(), change.target(),
                    displayBefore, displayAfter, change.beforeSha256(), change.afterSha256()));
        }
        String displayDigest = LocalProjectRootCipher.digest(
                canonicalPreview(preview));
        JsonNode request = decrypt(task.encryptedRequest(), principal(device), task.id());
        ObjectNode stored = json.createObjectNode()
                .put("rootId", request.path("rootId").asText())
                .put("planDigest", digest);
        stored.set("changes", output.path("changes").deepCopy());
        ClientProjectOperation operation = new ClientProjectOperation(task.id(), task.tenantId(),
                task.ownerId(), task.projectId(), task.agentId(), task.conversationId(),
                task.runId(), request.path("undoOf").isTextual()
                        ? UUID.fromString(request.path("undoOf").asText()) : null,
                digest, displayDigest, exact,
                encrypt(stored), ClientProjectOperation.State.PROPOSED, 1, now, now);
        ObjectNode result = json.createObjectNode().put("operationId", operation.id().toString())
                .put("version", operation.version()).put("digest", operation.planDigest())
                .put("displayDigest", operation.displayDigest())
                .put("displayExact", operation.displayExact());
        result.set("changes", json.valueToTree(preview));
        inTransaction(() -> {
            if (!operations.insert(operation)) {
                throw new IllegalStateException("设备项目变更操作无法登记");
            }
            if (!tasks.complete(device.tenantId(), device.ownerId(), device.id(), task.id(),
                    task.version(), ClientProjectTask.State.SUCCEEDED, encrypt(result),
                    "", now)) {
                throw stale(device, task.id());
            }
            return Boolean.TRUE;
        });
        record(device.tenantId(), device.ownerId(), task.id(),
                "CLIENT_PROJECT_CHANGE_PLAN", "SUCCEEDED");
    }

    private void completeChangeApply(LocalClientDevice device, ClientProjectTask task,
            JsonNode output, Instant now) {
        ClientProjectOperation operation = operations.find(task.tenantId(), task.ownerId(),
                task.operationId()).orElseThrow(() -> reject(device, task.id(),
                        "CLIENT_PROJECT_OPERATION_NOT_FOUND", 404,
                        "变更应用对应的操作不存在"));
        if (operation.state() != ClientProjectOperation.State.APPLYING
                || !operation.planDigest().equals(output.path("planDigest").asText())) {
            throw reject(device, task.id(), "CLIENT_PROJECT_APPLY_INVALID", 409,
                    "变更执行许可已失效或摘要不一致");
        }
        JsonNode plan = decryptJson(operation.encryptedPlan(), principal(device), operation.id());
        String expectedDigest = planDigest(planChanges(plan));
        if (!expectedDigest.equals(operation.planDigest())) {
            throw reject(device, task.id(), "CLIENT_PROJECT_PLAN_INVALID", 409,
                    "存储变更计划完整性核验失败");
        }
        JsonNode observed = output.path("observed");
        if (!observed.isObject()) {
            throw reject(device, task.id(), "CLIENT_PROJECT_APPLY_INVALID", 400,
                    "设备未返回可核验的写后版本");
        }
        for (PlanChange change : planChanges(plan)) {
            String observedPath = change.kind().equals("MOVE_FILE")
                    ? change.target() : change.path();
            if (!observed.has(observedPath)) {
                throw reject(device, task.id(), "CLIENT_PROJECT_APPLY_INVALID", 409,
                        "设备写后证据缺少计划路径");
            }
            String after = observed.path(observedPath).path("after").asText("");
            if (change.kind().equals("DELETE_FILE")
                    || change.kind().equals("DELETE_EMPTY_DIRECTORY")
                    || change.kind().equals("CREATE_DIRECTORY")) continue;
            if (!after.matches("[0-9a-f]{64}")
                    || !after.equals(change.afterSha256())) {
                throw reject(device, task.id(), "CLIENT_PROJECT_APPLY_INVALID", 409,
                        "设备写后摘要与已批准计划不一致");
            }
        }
        ObjectNode result = json.createObjectNode().put("operationId", operation.id().toString())
                .put("state", "SUCCEEDED").put("digest", operation.planDigest());
        result.set("observed", observed.deepCopy());
        inTransaction(() -> {
            operations.advance(operation.tenantId(), operation.ownerId(), operation.id(),
                    operation.version(), ClientProjectOperation.State.SUCCEEDED, now)
                    .orElseThrow(() -> new IllegalStateException("变更成功状态无法保存"));
            if (!tasks.complete(device.tenantId(), device.ownerId(), device.id(), task.id(),
                    task.version(), ClientProjectTask.State.SUCCEEDED, encrypt(result),
                    "", now)) {
                throw stale(device, task.id());
            }
            return Boolean.TRUE;
        });
        record(device.tenantId(), device.ownerId(), task.id(),
                "CLIENT_PROJECT_CHANGE_APPLY", "SUCCEEDED");
    }

    private void completeSelection(LocalClientDevice device, ClientProjectTask task,
            JsonNode output) {
        if (output == null || !output.isObject()) {
            throw reject(device, task.id(), "CLIENT_PROJECT_RESULT_INVALID", 400,
                    "目录选择结果缺少根标识或目录身份");
        }
        UUID clientRootId = uuid(output, "rootId");
        String rootIdentity = text(output, "rootIdentity", 64);
        if (!rootIdentity.matches("[0-9a-f]{64}")) {
            throw reject(device, task.id(), "CLIENT_PROJECT_RESULT_INVALID", 400,
                    "目录身份摘要无效");
        }
        String displayName = validName(text(output, "displayName", 120));
        Instant now = clock.instant();
        inTransaction(() -> {
            ClientProject project = projects.findByRoot(device.tenantId(), device.ownerId(),
                    device.id(), rootIdentity).orElse(null);
            if (project == null) {
                project = new ClientProject(UUID.randomUUID(), device.tenantId(), device.ownerId(),
                        device.id(), clientRootId, rootIdentity, displayName,
                        ClientProject.State.ACTIVE, 1, now, now);
                if (!projects.insert(project)) {
                    project = projects.findByRoot(device.tenantId(), device.ownerId(),
                            device.id(), rootIdentity).orElseThrow(
                                    () -> new IllegalStateException("设备项目并发登记冲突"));
                }
            } else if (!project.clientRootId().equals(clientRootId)) {
                throw reject(device, task.id(), "CLIENT_PROJECT_ROOT_CONFLICT", 409,
                        "同一目录身份已绑定到其他客户端根标识");
            }
            ObjectNode result = json.createObjectNode().put("projectId", project.id().toString());
            if (!tasks.complete(device.tenantId(), device.ownerId(), device.id(), task.id(),
                    task.version(), ClientProjectTask.State.SUCCEEDED, encrypt(result), "", now)) {
                throw stale(device, task.id());
            }
            record(device.tenantId(), device.ownerId(), task.id(),
                    "CLIENT_PROJECT_SELECTION_COMPLETE", "SUCCEEDED");
            return project;
        });
    }

    private void closeTask(ClientProjectTask task, ClientProjectTask.State state,
            String reason, Instant now) {
        inTransaction(() -> {
            if (isCommandTask(task.kind()) && clientProjectCommands != null) {
                clientProjectCommands.taskClosed(task, state, now);
            }
            if (task.kind() == ClientProjectTask.Kind.APPLY_CHANGE
                    && task.operationId() != null) {
                ClientProjectOperation operation = operations.lock(task.tenantId(),
                        task.ownerId(), task.operationId()).orElse(null);
                if (operation != null
                        && operation.state() == ClientProjectOperation.State.APPLYING) {
                    ClientProjectOperation.State operationState
                            = state == ClientProjectTask.State.CANCELLED
                            ? ClientProjectOperation.State.CANCELLED
                            : ClientProjectOperation.State.UNKNOWN;
                    operations.advance(task.tenantId(), task.ownerId(), operation.id(),
                            operation.version(), operationState, now).orElseThrow(
                                    () -> new IllegalStateException("任务过期时操作状态无法保存"));
                }
            }
            if (!tasks.close(task.tenantId(), task.ownerId(), task.deviceId(), task.id(),
                    task.version(), state, reason, now)) {
                throw new IllegalStateException("设备项目任务过期状态无法保存");
            }
            return Boolean.TRUE;
        });
    }

    private static boolean isCommandTask(ClientProjectTask.Kind kind) {
        return kind == ClientProjectTask.Kind.LIST_TOOLCHAINS
                || kind == ClientProjectTask.Kind.PLAN_COMMAND
                || kind == ClientProjectTask.Kind.RUN_COMMAND
                || kind == ClientProjectTask.Kind.COMMAND_STATUS
                || kind == ClientProjectTask.Kind.STOP_COMMAND;
    }

    private ClientProject activeBoundProject(PrincipalRef principal,
            ClientProjectBinding binding) {
        ClientProject project = activeProject(principal, binding.projectId());
        if (!project.deviceId().equals(binding.deviceId())
                || !project.clientRootId().equals(binding.clientRootId())
                || !project.rootIdentity().equals(binding.rootIdentity())
                || !project.ownerId().equals(principal.principalId())) {
            throw reject(principal, project.id(), "CLIENT_PROJECT_BINDING_CONFLICT", 409,
                    "设备项目来源与当前运行不一致");
        }
        return project;
    }

    private List<PlanChange> planChanges(JsonNode output) {
        if (output == null || !output.path("changes").isArray()
                || output.path("changes").isEmpty()
                || output.path("changes").size() > 20) {
            throw new IllegalArgumentException("变更计划动作数量无效");
        }
        List<PlanChange> changes = new ArrayList<>();
        for (JsonNode value : output.path("changes")) {
            if (!value.isObject()) throw new IllegalArgumentException("变更计划项无效");
            String kind = value.path("kind").asText("");
            if (!List.of("CREATE_FILE", "REPLACE_FILE", "DELETE_FILE",
                    "MOVE_FILE", "CREATE_DIRECTORY", "DELETE_EMPTY_DIRECTORY").contains(kind)) {
                throw new IllegalArgumentException("变更动作不在首版支持范围");
            }
            String path = requiredPlanText(value, "path", 1024);
            path = com.cmagent.core.runtime.LocalProjectPathPolicy.requireRelative(path, false);
            if (com.cmagent.core.runtime.LocalProjectPathPolicy.excluded(path)) {
                throw new IllegalArgumentException("变更路径属于受限目录或类型");
            }
            String beforeSha = nullableText(value, "beforeSha256");
            String beforeIdentity = nullableText(value, "beforeIdentity");
            String afterSha = nullableText(value, "afterSha256");
            if (beforeSha != null && !beforeSha.matches("[0-9a-f]{64}")
                    || afterSha != null && !afterSha.matches("[0-9a-f]{64}")
                    || beforeIdentity != null && beforeIdentity.length() > 128) {
                throw new IllegalArgumentException("变更版本字段无效");
            }
            String before = nullableText(value, "before");
            String after = nullableText(value, "after");
            String target = nullableText(value, "target");
            if (target != null) {
                target = com.cmagent.core.runtime.LocalProjectPathPolicy.requireRelative(target, false);
                if (com.cmagent.core.runtime.LocalProjectPathPolicy.excluded(target)) {
                    throw new IllegalArgumentException("变更目标属于受限目录或类型");
                }
            }
            if (kind.equals("MOVE_FILE") && target == null) {
                throw new IllegalArgumentException("移动动作缺少目标路径");
            }
            if (before != null && before.length() > 1_048_576
                    || after != null && after.length() > 1_048_576) {
                throw new IllegalArgumentException("变更正文过大");
            }
            changes.add(new PlanChange(kind, path, target,
                    beforeSha, beforeIdentity, afterSha, before, after));
        }
        changes.sort(Comparator.comparing(PlanChange::path));
        return List.copyOf(changes);
    }

    private static String planDigest(List<PlanChange> changes) {
        StringBuilder value = new StringBuilder("cm-agent/client-project/change/v1\n");
        for (PlanChange change : changes) {
            value.append(change.kind()).append('\0').append(change.path()).append('\0')
                    .append(change.target()).append('\0').append(change.beforeSha256()).append('\0')
                    .append(change.before()).append('\0').append(change.afterSha256()).append('\0')
                    .append(change.after()).append('\n');
        }
        return LocalProjectRootCipher.digest(value.toString());
    }

    private static String canonicalPreview(List<PreviewChange> changes) {
        StringBuilder value = new StringBuilder("cm-agent/client-project/preview/v1\n");
        for (PreviewChange change : changes) {
            value.append(change.kind()).append('\0').append(change.path()).append('\0')
                    .append(change.target()).append('\0').append(change.beforeSha256()).append('\0')
                    .append(change.before()).append('\0').append(change.afterSha256()).append('\0')
                    .append(change.after()).append('\n');
        }
        return value.toString();
    }

    private JsonNode decryptJson(String ciphertext, PrincipalRef principal, UUID id) {
        return decrypt(ciphertext, principal, id);
    }

    private String redact(String value) {
        return value == null ? null : redactor.redact(value);
    }

    private static String nullableText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("变更文本字段无效");
        return value.textValue();
    }

    private static String requiredPlanText(JsonNode object, String field, int max) {
        String value = nullableText(object, field);
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("变更必需字段无效");
        }
        return value;
    }

    private static PrincipalRef principal(LocalClientDevice device) {
        return new PrincipalRef(device.tenantId(), device.ownerId(), device.displayName(),
                java.util.Set.of());
    }

    private ClientProject activeProject(PrincipalRef principal, UUID projectId) {
        var project = projects.find(principal.tenantId(), principal.principalId(), projectId)
                .orElseThrow(() -> reject(principal, projectId,
                        "CLIENT_PROJECT_NOT_FOUND", 404, "设备项目不存在或不可访问"));
        if (project.state() != ClientProject.State.ACTIVE) {
            throw reject(principal, projectId, "CLIENT_PROJECT_ARCHIVED", 409,
                    "设备项目已归档");
        }
        return project;
    }

    private LocalClientDevice activeDevice(PrincipalRef principal, UUID deviceId, UUID resourceId) {
        return devices.find(principal.tenantId(), principal.principalId(), deviceId)
                .filter(value -> value.state() == LocalClientDevice.State.ACTIVE)
                .orElseThrow(() -> reject(principal, resourceId,
                        "CLIENT_PROJECT_DEVICE_UNAVAILABLE", 409,
                        "本机设备不存在、已撤销或不属于当前账号"));
    }

    private ResultPayload result(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_RESULT_BYTES) {
            throw new IllegalArgumentException("设备项目任务结果大小无效");
        }
        try {
            ResultPayload value = json.readValue(body, ResultPayload.class);
            if (value == null || value.state() == null || value.expectedVersion() < 2
                    || value.state() != ClientProjectTask.State.SUCCEEDED
                    && value.state() != ClientProjectTask.State.FAILED
                    && value.state() != ClientProjectTask.State.UNKNOWN) {
                throw new IllegalArgumentException("设备项目任务终态无效");
            }
            return value;
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("设备项目任务结果格式无效", invalid);
        }
    }

    private JsonNode decrypt(String ciphertext, PrincipalRef principal, UUID resourceId) {
        try {
            return json.readTree(cipher.decrypt(ciphertext));
        } catch (Exception failure) {
            throw infrastructure(principal, resourceId, "CLIENT_PROJECT_DECRYPT", failure);
        }
    }

    private JsonNode decrypt(String ciphertext, LocalClientDevice device, UUID resourceId) {
        return decrypt(ciphertext, new PrincipalRef(device.tenantId(), device.ownerId(),
                device.displayName(), java.util.Set.of()), resourceId);
    }

    private String encrypt(JsonNode value) {
        try {
            return cipher.encrypt(json.writeValueAsString(value));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("设备项目任务载荷无法加密", failure);
        }
    }

    private JsonNode redactResult(ClientProjectTask.Kind kind, JsonNode source) {
        JsonNode value = source == null ? json.createObjectNode() : source.deepCopy();
        if (kind == ClientProjectTask.Kind.READ && value.isObject()) {
            ObjectNode object = (ObjectNode) value;
            if (object.path("content").isTextual()) {
                object.put("content", redactor.redact(object.path("content").textValue()));
            }
        } else if (kind == ClientProjectTask.Kind.SEARCH && value.path("matches").isArray()) {
            value.path("matches").forEach(match -> {
                if (match instanceof ObjectNode object && object.path("excerpt").isTextual()) {
                    object.put("excerpt", redactor.redact(object.path("excerpt").textValue()));
                }
            });
        }
        return value;
    }

    private static UUID uuid(JsonNode output, String field) {
        String value = text(output, field, 36);
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("设备项目结果标识无效");
        }
    }

    private static String text(JsonNode output, String field, int maximum) {
        JsonNode value = output.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > maximum) {
            throw new IllegalArgumentException("设备项目结果字段无效");
        }
        return value.textValue();
    }

    private static String validName(String value) {
        if (value == null || value.isBlank() || value.length() > 120
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("项目展示名无效");
        }
        return value.strip();
    }

    private void authorize(PrincipalRef principal, String permission, UUID resourceId) {
        if (!permissions.check(principal, permission).allowed()) {
            throw reject(principal, resourceId, "CLIENT_PROJECT_FORBIDDEN", 403,
                    "当前账号缺少设备项目操作权限");
        }
    }

    private LocalProjectAccessException stale(Object identity, UUID taskId) {
        PrincipalRef principal = identity instanceof LocalClientDevice device
                ? new PrincipalRef(device.tenantId(), device.ownerId(), device.displayName(),
                        java.util.Set.of())
                : (PrincipalRef) identity;
        return reject(principal, taskId, "CLIENT_PROJECT_TASK_STALE", 409,
                "设备项目任务状态已变化，结果未重复应用");
    }

    private LocalProjectAccessException reject(Object identity, UUID id, String code, int status,
            String message) {
        PrincipalRef principal = identity instanceof LocalClientDevice device
                ? new PrincipalRef(device.tenantId(), device.ownerId(), device.displayName(),
                        java.util.Set.of())
                : (PrincipalRef) identity;
        String errorId = UUID.randomUUID().toString();
        try {
            audit.appendControlled(principal.tenantId(), principal.principalId(),
                    "CLIENT_PROJECT_ACCESS", "CLIENT_PROJECT",
                    id == null ? "-" : id.toString(), "DENIED",
                    "errorCode=" + code + " errorId=" + errorId);
        } catch (RuntimeException failure) {
            return infrastructure(principal, id, "CLIENT_PROJECT_AUDIT", failure);
        }
        return new LocalProjectAccessException(code, errorId, status, message);
    }

    private LocalProjectAccessException infrastructure(Object identity, UUID id, String action,
            Throwable failure) {
        PrincipalRef principal = identity instanceof PrincipalRef value ? value
                : identity instanceof LocalClientDevice device
                    ? new PrincipalRef(device.tenantId(), device.ownerId(), device.displayName(),
                            java.util.Set.of())
                    : new PrincipalRef(UUID.fromString("00000000-0000-0000-0000-000000000000"),
                            "-", "-", java.util.Set.of());
        String errorId = UUID.randomUUID().toString();
        diagnostics.error(new ErrorDiagnosticLogger.DiagnosticContext(errorId, action,
                "CLIENT_PROJECT_UNAVAILABLE", principal.tenantId().toString(),
                principal.principalId(), null, null, null, null, "CLIENT_PROJECT"),
                failure, "CLIENT_PROJECT", id == null ? "-" : id.toString());
        return new LocalProjectAccessException("CLIENT_PROJECT_UNAVAILABLE", errorId, 503,
                "设备项目服务暂不可用，请凭错误编号检查配置或联系管理员");
    }

    private void record(PrincipalRef principal, UUID id, String action, String status) {
        record(principal.tenantId(), principal.principalId(), id, action, status);
    }

    private void record(UUID tenantId, String ownerId, UUID id, String action, String status) {
        try {
            audit.appendControlled(tenantId, ownerId, action, "CLIENT_PROJECT_TASK",
                    id.toString(), status, "设备项目固定任务");
        } catch (RuntimeException failure) {
            throw infrastructure(new PrincipalRef(tenantId, ownerId, "设备客户端",
                    java.util.Set.of()), id, action, failure);
        }
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    private TaskView view(ClientProjectTask task) {
        return new TaskView(task.id(), task.kind().name(), task.state().name(),
                task.expiresAt());
    }

    private ProjectView projectView(ClientProject project) {
        return new ProjectView(project.id(), project.name(), project.deviceId(),
                project.state().name(), project.version(), project.createdAt(), project.updatedAt());
    }

    /**
     * @param operationId 操作编号
     * @param state 当前状态
     * @param version 审批绑定的计划版本
     * @param digest 原始计划摘要
     * @param displayDigest 脱敏预览摘要
     * @param displayExact 脱敏预览是否保留全部实际差异
     * @param createdAt 创建时间
     * @param changes 已脱敏精确差异
     */
    public record ChangePreview(UUID operationId, String state, long version, String digest,
            String displayDigest, boolean displayExact, Instant createdAt,
            List<PreviewChange> changes) {
        public ChangePreview {
            changes = List.copyOf(changes);
        }
        @Override public String toString() {
            return "ChangePreview[受保护客户端项目差异]";
        }
    }

    /**
     * @param kind 变更动作
     * @param path 项目内路径
     * @param target 目标路径
     * @param before 脱敏后的原正文
     * @param after 脱敏后的新正文
     * @param beforeSha256 原字节摘要
     * @param afterSha256 新字节摘要
     */
    public record PreviewChange(String kind, String path, String target, String before,
            String after, String beforeSha256, String afterSha256) {
        @Override public String toString() {
            return "PreviewChange[受保护客户端项目差异]";
        }
    }

    /**
     * @param operationId 操作编号
     * @param projectId 固定项目
     * @param runId 固定 Run
     * @param state 操作状态
     * @param version 条件版本
     * @param digest 原始计划摘要
     * @param displayDigest 脱敏预览摘要
     * @param displayExact 是否可据此批准
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record OperationView(UUID operationId, UUID projectId, UUID runId, String state,
            long version, String digest, String displayDigest, boolean displayExact,
            Instant createdAt, Instant updatedAt) { }

    private record PlanChange(String kind, String path, String target, String beforeSha256,
            String beforeIdentity, String afterSha256, String before, String after) { }

    /** 浏览器任务句柄，不包含设备请求密文或客户端根标识。 */
    public record TaskView(UUID requestId, String kind, String state, Instant expiresAt) { }

    /** 浏览器轮询结果，成功输出只来自原主体任务。 */
    public record TaskStatusView(UUID requestId, String kind, String state, String error,
            JsonNode output, ProjectView project) { }

    /** 设备任务响应，类别和请求均由 Server 固定。 */
    public record DeviceTaskView(UUID taskId, String category, long version, JsonNode request) {
        public DeviceTaskView {
            Objects.requireNonNull(taskId);
            Objects.requireNonNull(category);
            Objects.requireNonNull(request);
        }
    }

    /** 项目页可见字段，不包含客户端根或目录身份。 */
    public record ProjectView(UUID id, String name, UUID deviceId, String state, long version,
            Instant createdAt, Instant updatedAt) { }

    /** 固定只读查询参数，字段按任务种类再校验。 */
    public record ReadRequest(String path, String cursor, int limit, int offset, int maxBytes,
            String query, boolean content) {
        public ReadRequest {
            if (path != null && (path.length() > 1024 || path.indexOf('\0') >= 0)
                    || cursor != null && (cursor.length() > 1024 || cursor.indexOf('\0') >= 0)
                    || query != null && (query.length() > 256 || query.indexOf('\0') >= 0)
                    || limit < 1 || limit > 200 || offset < 0 || offset > 2_097_152
                    || maxBytes < 4 || maxBytes > 786_432) {
                throw new IllegalArgumentException("设备项目查询参数无效");
            }
        }
    }

    private record ResultPayload(ClientProjectTask.State state, long expectedVersion,
            JsonNode output, String error) { }
}
