package com.cmagent.server.runtime;

import com.cmagent.core.domain.ClientProject;
import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.ClientProjectTask;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolApprovalHistoryPageRequest;
import com.cmagent.core.repository.ClientProjectBindingRepository;
import com.cmagent.core.repository.ClientProjectOperationRepository;
import com.cmagent.core.repository.ClientProjectRepository;
import com.cmagent.core.repository.ClientProjectTaskRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.ToolApprovalRepository;
import com.cmagent.core.runtime.ToolInvocationInfrastructureException;
import com.cmagent.core.runtime.ToolInvocationRequest;
import com.cmagent.core.runtime.ToolInvocationResult;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.ClientProjectProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 仅在真实项目 Run 和固定 grant 复核后执行设备项目工具。
 */
public final class ClientProjectTools {
    private static final long WAIT_MILLIS = 50L;
    private final RunRepository runs;
    private final ClientProjectRepository projects;
    private final ClientProjectBindingRepository bindings;
    private final ClientProjectOperationRepository operations;
    private final ClientProjectCommandService commandService;
    private final ToolApprovalRepository approvals;
    private final ClientProjectTaskRepository tasks;
    private final ClientProjectToolCatalog catalog;
    private final ClientProjectService service;
    private final PermissionEvaluator permissions;
    private final AuditAppender audit;
    private final ObjectMapper json;
    private final ClientProjectProperties properties;
    private final Clock clock;
    private final ClientProjectReadSnapshots snapshots;

    public ClientProjectTools(RunRepository runs, ClientProjectRepository projects,
            ClientProjectBindingRepository bindings, ClientProjectOperationRepository operations,
            ClientProjectCommandService commandService, ToolApprovalRepository approvals,
            ClientProjectTaskRepository tasks,
            ClientProjectToolCatalog catalog, ClientProjectService service,
            PermissionEvaluator permissions, AuditAppender audit, ObjectMapper json,
            ClientProjectProperties properties, Clock clock,
            ClientProjectReadSnapshots snapshots) {
        this.runs = Objects.requireNonNull(runs);
        this.projects = Objects.requireNonNull(projects);
        this.bindings = Objects.requireNonNull(bindings);
        this.operations = Objects.requireNonNull(operations);
        this.commandService = Objects.requireNonNull(commandService);
        this.approvals = Objects.requireNonNull(approvals);
        this.tasks = Objects.requireNonNull(tasks);
        this.catalog = Objects.requireNonNull(catalog);
        this.service = Objects.requireNonNull(service);
        this.permissions = Objects.requireNonNull(permissions);
        this.audit = Objects.requireNonNull(audit);
        this.json = Objects.requireNonNull(json);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    boolean handles(ToolInvocationRequest request) {
        return ClientProjectToolCatalog.managed(request.tenantId(), request.toolId());
    }

    public ToolInvocationResult invoke(ToolInvocationRequest request, ToolDefinition tool) {
        if (!permissions.check(request.principal(), "project:read").allowed()) {
            return ToolInvocationResult.deniedProtected("当前账号缺少设备项目读取权限");
        }
        RunRecord run = runs.findByTenantAndAgentAndId(request.tenantId(), request.agentId(),
                request.runId()).orElse(null);
        if (run == null || !run.principalId().equals(request.principal().principalId())
                || run.status() != RunStatus.RUNNING && run.status() != RunStatus.WAITING_APPROVAL
                || runs.workspaceProtocol(request.tenantId(), request.agentId(), request.runId())
                    != ClientProjectBinding.PROTOCOL
                || !catalog.valid(tool)) {
            return ToolInvocationResult.deniedProtected("当前运行没有该设备项目工具的有效授权");
        }
        ClientProjectBinding binding = bindings.run(request.tenantId(),
                request.principal().principalId(), request.runId()).orElse(null);
        if (binding == null || !binding.agentId().equals(request.agentId())) {
            return ToolInvocationResult.deniedProtected("设备项目运行来源缺失或不一致");
        }
        ClientProject project = projects.find(binding.tenantId(), binding.ownerId(),
                binding.projectId()).orElse(null);
        if (project == null || project.state() != ClientProject.State.ACTIVE
                || !project.deviceId().equals(binding.deviceId())
                || !project.clientRootId().equals(binding.clientRootId())
                || !project.rootIdentity().equals(binding.rootIdentity())) {
            return ToolInvocationResult.deniedProtected("设备项目来源已变化或不可用");
        }
        try {
            ClientProjectToolCatalog.Kind kind = ClientProjectToolCatalog
                    .kind(request.tenantId(), request.toolId()).orElseThrow();
            Map<String, Object> input = json.readerFor(
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { })
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readValue(request.inputJson());
            JsonNode output;
            if (kind == ClientProjectToolCatalog.Kind.READ) {
                keys(input, Set.of("path", "offset", "maxBytes", "expectedSha256"));
                output = json.valueToTree(snapshots.read(request.principal(), binding,
                        string(input, "path", ""), integer(input, "offset", 0, 0, 2_097_152),
                        integer(input, "maxBytes", 65_536, 4, 65_536),
                        string(input, "expectedSha256", null)));
            } else if (kind == ClientProjectToolCatalog.Kind.SEARCH) {
                keys(input, Set.of("query", "content"));
                output = json.valueToTree(snapshots.search(request.principal(), binding,
                        string(input, "query", ""), bool(input, "content", false)));
            } else if (kind == ClientProjectToolCatalog.Kind.LIST) {
                keys(input, Set.of("path", "cursor", "limit"));
                var read = readRequest(kind, input);
                UUID requestId = UUID.randomUUID();
                var handle = service.startQuery(request.principal(), project.id(), requestId,
                        ClientProjectTask.Kind.LIST, read);
                output = await(request.principal(), handle, requestId);
            } else if (kind == ClientProjectToolCatalog.Kind.PROPOSE_CHANGE) {
                keys(input, Set.of("changes"));
                requirePermission(request, "project:write");
                JsonNode changes = input.get("changes") == null ? null
                        : json.valueToTree(input.get("changes"));
                var handle = service.startChangePlan(request.principal(), binding, changes, null);
                output = await(request.principal(), handle, handle.requestId());
            } else if (kind == ClientProjectToolCatalog.Kind.PROPOSE_UNDO) {
                keys(input, Set.of("operationId"));
                requirePermission(request, "project:write");
                UUID original = uuid(input.get("operationId"));
                var handle = service.startUndoPlan(request.principal(), binding, original);
                output = await(request.principal(), handle, handle.requestId());
            } else if (kind == ClientProjectToolCatalog.Kind.CHANGE_STATUS) {
                keys(input, Set.of("operationId"));
                requirePermission(request, "project:read");
                output = json.valueToTree(service.operation(request.principal(), project.id(),
                        uuid(input.get("operationId"))));
            } else if (kind == ClientProjectToolCatalog.Kind.LIST_TOOLCHAINS) {
                keys(input, Set.of());
                requirePermission(request, "project:execute");
                var handle = commandService.startToolchains(request.principal(), binding);
                output = await(request.principal(), handle, handle.requestId());
            } else if (kind == ClientProjectToolCatalog.Kind.PROPOSE_COMMAND) {
                keys(input, Set.of("toolchainId", "arguments", "relativeDirectory",
                        "environment", "timeoutSeconds", "maxOutputBytes"));
                requirePermission(request, "project:execute");
                var handle = commandService.startPlan(request.principal(), binding,
                        json.valueToTree(input));
                output = await(request.principal(), handle, handle.requestId());
            } else if (kind == ClientProjectToolCatalog.Kind.RUN_COMMAND) {
                keys(input, Set.of("operationId", "version", "digest"));
                requirePermission(request, "project:execute");
                UUID commandId = uuid(input.get("operationId"));
                long version = number(input.get("version"));
                String digest = text(input.get("digest"));
                if (!exactApproval(request, binding, input)) {
                    return ToolInvocationResult.deniedProtected(
                            "设备项目命令尚未取得当前调用的精确审批");
                }
                var handle = commandService.startRun(request.principal(), binding, commandId,
                        version, digest, request.cancellation());
                output = await(request.principal(), handle, handle.requestId());
            } else if (kind == ClientProjectToolCatalog.Kind.COMMAND_STATUS) {
                keys(input, Set.of("operationId"));
                requirePermission(request, "project:read");
                var handle = commandService.startStatus(request.principal(), project.id(),
                        uuid(input.get("operationId")));
                output = await(request.principal(), handle, handle.requestId());
            } else if (kind == ClientProjectToolCatalog.Kind.CANCEL_COMMAND) {
                keys(input, Set.of("operationId"));
                requirePermission(request, "project:execute");
                var handle = commandService.startStop(request.principal(), project.id(),
                        uuid(input.get("operationId")));
                output = await(request.principal(), handle, handle.requestId());
            } else {
                keys(input, Set.of("operationId", "version", "digest"));
                requirePermission(request, "project:write");
                UUID operationId = uuid(input.get("operationId"));
                long version = number(input.get("version"));
                String digest = text(input.get("digest"));
                if (!exactApproval(request, binding, input)) {
                    return ToolInvocationResult.deniedProtected(
                            "设备项目变更尚未取得当前调用的精确审批");
                }
                var operation = operations.find(binding.tenantId(), binding.ownerId(), operationId)
                        .orElse(null);
                if (operation == null || operation.version() != version
                        || !operation.planDigest().equals(digest)) {
                    return ToolInvocationResult.deniedProtected(
                            "设备项目变更版本、摘要或状态已变化");
                }
                var handle = service.startApply(request.principal(), binding, operationId,
                        version, digest);
                output = await(request.principal(), handle, handle.requestId());
                snapshots.afterApply(request.principal(), binding,
                        service.changePreview(request.principal(), project.id(), operationId));
            }
            return ToolInvocationResult.succeededProtected(json.writeValueAsString(output),
                    "设备项目工具已完成，请在项目面板查看结果");
        } catch (LocalProjectAccessException failure) {
            return ToolInvocationResult.failedProtected(failure.getMessage() + "（"
                    + failure.errorCode() + "，错误编号：" + failure.errorId() + "）");
        } catch (IllegalArgumentException | java.io.IOException failure) {
            return ToolInvocationResult.failedProtected("设备项目工具参数或结果无效");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ToolInvocationInfrastructureException("设备项目工具等待被中断", interrupted);
        } catch (RuntimeException failure) {
            throw new ToolInvocationInfrastructureException("设备项目工具支撑设施不可用", failure);
        }
    }

    private JsonNode await(com.cmagent.api.PrincipalRef principal,
            ClientProjectService.TaskView handle, UUID requestId) throws InterruptedException {
        long deadline = System.nanoTime() + properties.getQueryTtl().toNanos();
        while (System.nanoTime() < deadline) {
            var status = service.taskStatus(principal, requestId);
            if ("SUCCEEDED".equals(status.state())) return status.output();
            if ("FAILED".equals(status.state()) || "UNKNOWN".equals(status.state())
                    || "CANCELLED".equals(status.state())) {
                throw new IllegalStateException("设备项目任务未成功");
            }
            Thread.sleep(WAIT_MILLIS);
        }
        var task = tasks.findForOwner(principal.tenantId(), principal.principalId(), requestId)
                .orElse(null);
        if (task != null) {
            try {
                tasks.close(task.tenantId(), task.ownerId(), task.deviceId(), task.id(),
                        task.version(), ClientProjectTask.State.UNKNOWN,
                        "设备项目工具等待超时", clock.instant());
            } catch (RuntimeException ignored) {
                // 原始工具调用仍以受控超时结束，不自动重发。
            }
        }
        throw new IllegalStateException("设备项目工具等待超时");
    }

    private ClientProjectService.ReadRequest readRequest(ClientProjectToolCatalog.Kind kind,
            Map<String, Object> input) {
        int limit = integer(input, "limit", 100, 1, 200);
        int offset = integer(input, "offset", 0, 0, 2_097_152);
        int maxBytes = integer(input, "maxBytes", 65_536, 4, 65_536);
        String path = string(input, "path", "");
        String cursor = string(input, "cursor", "");
        String query = string(input, "query", "");
        boolean content = bool(input, "content", false);
        return new ClientProjectService.ReadRequest(path, cursor, limit, offset, maxBytes,
                query, content);
    }

    private void requirePermission(ToolInvocationRequest request, String permission) {
        if (!permissions.check(request.principal(), permission).allowed()) {
            throw new LocalProjectAccessException("CLIENT_PROJECT_FORBIDDEN",
                    UUID.randomUUID().toString(), 403, "当前账号缺少设备项目操作权限");
        }
    }

    private boolean exactApproval(ToolInvocationRequest request, ClientProjectBinding binding,
            Map<String, Object> input) {
        try {
            String canonical = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,
                    true).writeValueAsString(input);
            String hash = LocalProjectRootCipher.digest(canonical);
            return approvals.listHistory(binding.tenantId(), binding.agentId(),
                            binding.conversationId(),
                            new ToolApprovalHistoryPageRequest(100, null, null))
                    .stream()
                    .filter(value -> value.runId().equals(binding.runId())
                            && value.requestedBy().equals(binding.ownerId())
                            && binding.ownerId().equals(value.decidedBy())
                            && value.expiresAt().isAfter(clock.instant()))
                    .flatMap(value -> value.items().stream())
                    .anyMatch(item -> item.toolId().equals(request.toolId())
                            && item.toolCallId().equals(request.toolCallId())
                            && item.decision() == ToolApprovalDecision.APPROVE
                            && item.inputHash().equals(hash));
        } catch (java.io.IOException failure) {
            throw new ToolInvocationInfrastructureException("设备项目审批摘要无法核验", failure);
        }
    }

    private static UUID uuid(Object value) {
        String text = text(value);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("标识格式无效");
        }
    }

    private static long number(Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("版本无效");
        long result = number.longValue();
        if (result < 1 || number.doubleValue() != result) {
            throw new IllegalArgumentException("版本无效");
        }
        return result;
    }

    private static String text(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("字段无效");
        return text;
    }

    private static String string(Map<String, Object> input, String key, String fallback) {
        Object value = input.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String result)) throw new IllegalArgumentException("字段无效");
        return result;
    }

    private static void keys(Map<String, Object> input, Set<String> allowed) {
        if (input == null || !allowed.containsAll(input.keySet())) {
            throw new IllegalArgumentException("字段无效");
        }
    }

    private static int integer(Map<String, Object> input, String key, int fallback,
            int minimum, int maximum) {
        Object value = input.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < minimum || number.intValue() > maximum) {
            throw new IllegalArgumentException("整数无效");
        }
        return number.intValue();
    }

    private static boolean bool(Map<String, Object> input, String key, boolean fallback) {
        Object value = input.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException("布尔值无效");
        return result;
    }
}
