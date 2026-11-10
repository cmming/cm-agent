package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ClientProject;
import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.ClientProjectCommand;
import com.cmagent.core.domain.ClientProjectTask;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.repository.ClientProjectBindingRepository;
import com.cmagent.core.repository.ClientProjectCommandRepository;
import com.cmagent.core.repository.ClientProjectOperationRepository;
import com.cmagent.core.repository.ClientProjectRepository;
import com.cmagent.core.repository.ClientProjectTaskRepository;
import com.cmagent.core.repository.LocalClientDeviceRepository;
import com.cmagent.core.runtime.RunCancellation;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.ClientProjectProperties;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 已配对设备项目固定工具链命令的 Server 编排。
 *
 * <p>Server 只保存认证加密清单与脱敏输出，不接受模型提供可执行文件路径。命令计划和启动使用独立
 * 设备任务；HIGH 启动必须来自精确审批。模型结束后由独立虚拟线程等待命令状态收敛，成功 Run
 * 不会因模型提前回答而跳过该屏障。</p>
 */
public final class ClientProjectCommandService implements AutoCloseable {
    private static final long WAIT_MILLIS = 500;
    private static final int MAX_COMMAND_BYTES = 1_048_576;
    private final ClientProjectCommandRepository commands;
    private final ClientProjectOperationRepository projectOperations;
    private final ClientProjectTaskRepository tasks;
    private final ClientProjectBindingRepository bindings;
    private final ClientProjectRepository projects;
    private final LocalClientDeviceRepository devices;
    private final PermissionEvaluator permissions;
    private final AuditAppender audit;
    private final ModelCredentialCipher cipher;
    private final SensitiveDataRedactor redactor;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final ClientProjectProperties properties;
    private final Clock clock;
    private final ExecutorService barrierWorkers = Executors.newVirtualThreadPerTaskExecutor();

    public ClientProjectCommandService(ClientProjectCommandRepository commands,
            ClientProjectOperationRepository projectOperations,
            ClientProjectTaskRepository tasks, ClientProjectBindingRepository bindings,
            ClientProjectRepository projects, LocalClientDeviceRepository devices,
            PermissionEvaluator permissions, AuditAppender audit,
            ModelCredentialCipher cipher, SensitiveDataRedactor redactor, ObjectMapper json,
            TransactionTemplate transaction, ClientProjectProperties properties, Clock clock) {
        this.commands = Objects.requireNonNull(commands);
        this.projectOperations = Objects.requireNonNull(projectOperations);
        this.tasks = Objects.requireNonNull(tasks);
        this.bindings = Objects.requireNonNull(bindings);
        this.projects = Objects.requireNonNull(projects);
        this.devices = Objects.requireNonNull(devices);
        this.permissions = Objects.requireNonNull(permissions);
        this.audit = Objects.requireNonNull(audit);
        this.cipher = Objects.requireNonNull(cipher);
        this.redactor = Objects.requireNonNull(redactor);
        this.json = Objects.requireNonNull(json);
        this.transaction = Objects.requireNonNull(transaction);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    public ClientProjectService.TaskView startToolchains(PrincipalRef principal,
            ClientProjectBinding binding) {
        require(principal, "project:execute");
        Source source = source(principal, binding, true);
        ObjectNode request = json.createObjectNode().put("kind", "LIST_TOOLCHAINS")
                .put("rootId", source.project().clientRootId().toString());
        return enqueue(principal, binding, source.project(), null,
                ClientProjectTask.Kind.LIST_TOOLCHAINS, request,
                "CLIENT_PROJECT_TOOLCHAINS_REQUESTED");
    }

    public ClientProjectService.TaskView startPlan(PrincipalRef principal,
            ClientProjectBinding binding, JsonNode request) {
        require(principal, "project:execute");
        Source source = source(principal, binding, true);
        if (projectOperations.hasUncertain(principal.tenantId(), principal.principalId(),
                source.project().id())) {
            throw new IllegalArgumentException("项目仍有文件变更副作用，请先核对");
        }
        if (request == null || !request.isObject() || request.toString().length() > 262_144) {
            throw new IllegalArgumentException("命令计划参数无效或过大");
        }
        ObjectNode payload = json.createObjectNode().put("kind", "PLAN_COMMAND")
                .put("rootId", source.project().clientRootId().toString());
        payload.setAll((ObjectNode) request.deepCopy());
        payload.put("kind", "PLAN_COMMAND")
                .put("rootId", source.project().clientRootId().toString());
        return enqueue(principal, binding, source.project(), null,
                ClientProjectTask.Kind.PLAN_COMMAND, payload,
                "CLIENT_PROJECT_COMMAND_PLAN");
    }

    public ClientProjectService.TaskView startRun(PrincipalRef principal,
            ClientProjectBinding binding, UUID commandId, long expectedVersion, String digest,
            RunCancellation cancellation) {
        require(principal, "project:execute");
        ClientProjectService.TaskView task = inTransaction(() -> {
            Source source = source(principal, binding, true);
            if (projectOperations.hasUncertain(principal.tenantId(), principal.principalId(),
                    source.project().id()) || commands.hasUnsettledForProject(
                            principal.tenantId(), principal.principalId(), source.project().id())) {
                throw new IllegalArgumentException("项目仍有执行中或结果未知的副作用，请先核对");
            }
            ClientProjectCommand command = commands.lock(principal.tenantId(),
                    principal.principalId(), commandId).orElseThrow(
                            () -> new IllegalArgumentException("命令不存在或不可访问"));
            if (!command.projectId().equals(source.project().id())
                    || !command.runId().equals(binding.runId())
                    || command.state() != ClientProjectCommand.State.PROPOSED
                    || command.version() != expectedVersion
                    || !command.planDigest().equals(digest) || !command.displayExact()) {
                throw new IllegalArgumentException("命令版本、摘要、展示或状态已变化");
            }
            JsonNode stored = decodePlan(command);
            ObjectNode request = json.createObjectNode().put("kind", "RUN_COMMAND")
                    .put("rootId", source.project().clientRootId().toString())
                    .put("operationId", command.id().toString())
                    .put("planDigest", command.planDigest());
            request.set("manifest", stored.path("manifest").deepCopy());
            ClientProjectTask queued = new ClientProjectTask(UUID.randomUUID(), command.tenantId(),
                    command.ownerId(), source.project().deviceId(), command.projectId(),
                    command.runId(), command.agentId(), command.conversationId(), null,
                    command.id(), ClientProjectTask.Kind.RUN_COMMAND, encrypt(request),
                    ClientProjectTask.State.PENDING, "", "", clock.instant(),
                    clock.instant().plus(properties.getCommandTaskTtl()), null, null, 1);
            if (!tasks.insert(queued)) throw new IllegalStateException("命令启动任务无法登记");
            commands.update(command.tenantId(), command.ownerId(), command.id(),
                    command.version(), command.update(ClientProjectCommand.State.QUEUED,
                            false, null, false, false, "", command.sequence(), clock.instant()))
                    .orElseThrow(() -> new IllegalStateException("命令启动许可已失效"));
            record(principal, command.id(), "CLIENT_PROJECT_COMMAND_RUN", "QUEUED");
            return view(queued);
        });
        if (cancellation != null) {
            cancellation.signal().thenRun(() -> requestRunStop(principal.tenantId(),
                    principal.principalId(), binding.runId()));
        }
        return task;
    }

    public ClientProjectService.TaskView startStatus(PrincipalRef principal, UUID projectId,
            UUID commandId) {
        require(principal, "project:read");
        ClientProjectCommand command = owned(principal, projectId, commandId);
        if (command.state() == ClientProjectCommand.State.PROPOSED) {
            throw new IllegalArgumentException("命令尚未启动，请先核对命令预览并完成启动审批");
        }
        ClientProjectBinding binding = binding(command);
        Source source = source(principal, binding, true);
        return enqueue(principal, binding, source.project(), commandId,
                ClientProjectTask.Kind.COMMAND_STATUS,
                commandRequest(binding, command, "COMMAND_STATUS"),
                "CLIENT_PROJECT_COMMAND_STATUS_REQUESTED");
    }

    public ClientProjectService.TaskView startStop(PrincipalRef principal, UUID projectId,
            UUID commandId) {
        require(principal, "project:execute");
        ClientProjectCommand command = owned(principal, projectId, commandId);
        ClientProjectBinding binding = binding(command);
        return inTransaction(() -> {
            Source source = source(principal, binding, true);
            ClientProjectCommand current = commands.lock(principal.tenantId(),
                    principal.principalId(), commandId).orElseThrow();
            if (!current.state().unsettled()) {
                throw new IllegalStateException("命令已经终态，请刷新状态");
            }
            if (current.state() == ClientProjectCommand.State.QUEUED) {
                throw new IllegalStateException("命令尚未确认启动，请等待设备状态后重试停止");
            }
            if (current.state() == ClientProjectCommand.State.STOP_REQUESTED
                    || current.stopRequested()) {
                // 重复停止只读取设备权威状态，避免生成多个停止任务造成进程控制竞态。
                return view(commandTask(source.project(), binding, commandId,
                        ClientProjectTask.Kind.COMMAND_STATUS,
                        commandRequest(binding, current, "COMMAND_STATUS"),
                        "CLIENT_PROJECT_COMMAND_STOP_STATUS"));
            }
            ClientProjectCommand stopping = current.state() == ClientProjectCommand.State.RUNNING
                    ? current.update(ClientProjectCommand.State.STOP_REQUESTED, true,
                            current.exitCode(), current.truncated(), current.outputAvailable(),
                            current.encryptedOutput(), current.sequence(), clock.instant())
                    : current;
            if (stopping != current) {
                commands.update(current.tenantId(), current.ownerId(), current.id(),
                        current.version(), stopping).orElseThrow(
                                () -> new IllegalStateException("命令停止状态无法保存"));
            }
            return view(commandTask(source.project(), binding, commandId,
                    ClientProjectTask.Kind.STOP_COMMAND,
                    commandRequest(binding, current, "STOP_COMMAND"),
                    "CLIENT_PROJECT_COMMAND_STOP"));
        });
    }

    public CommandView command(PrincipalRef principal, UUID projectId, UUID commandId) {
        require(principal, "project:read");
        return commandView(owned(principal, projectId, commandId));
    }

    public CommandPreview preview(PrincipalRef principal, UUID projectId, UUID commandId) {
        require(principal, "project:read");
        ClientProjectCommand command = owned(principal, projectId, commandId);
        JsonNode plan = decodePlan(command);
        return new CommandPreview(command.id(), command.state().name(), command.version(),
                command.planDigest(), command.displayDigest(), command.displayExact(),
                plan.path("preview"));
    }

    /**
     * 人工接受 UNKNOWN 的外部副作用，但保持命令和根隔离；不能伪报进程静止。
     *
     * @param expectedVersion 用户核对时的命令版本
     * @param acknowledgeExternalEffects 必须明确为 true
     * @return 仍为 UNKNOWN 的权威状态
     */
    public CommandView resolveUnknown(PrincipalRef principal, UUID projectId, UUID commandId,
            long expectedVersion, boolean acknowledgeExternalEffects) {
        require(principal, "project:execute");
        if (!acknowledgeExternalEffects) {
            throw new IllegalArgumentException("必须明确接受可能已经发生的外部副作用");
        }
        ClientProjectCommand command = owned(principal, projectId, commandId);
        if (command.state() != ClientProjectCommand.State.UNKNOWN
                || command.version() != expectedVersion) {
            throw new IllegalArgumentException("命令状态或版本已变化，请刷新后核对");
        }
        record(principal, commandId, "CLIENT_PROJECT_COMMAND_RECONCILED_UNKNOWN",
                "UNKNOWN");
        return commandView(command);
    }

    public CompletableFuture<Outcome> awaitRunSettled(PrincipalRef principal, RunRecord run) {
        List<ClientProjectCommand> all = commands.listForRun(
                principal.tenantId(), principal.principalId(), run.id());
        List<ClientProjectCommand> associated = all.stream()
                .filter(value -> value.state() != ClientProjectCommand.State.PROPOSED)
                .toList();
        List<ClientProjectCommand> initial = associated.stream()
                .filter(value -> value.state().unsettled()).toList();
        if (initial.isEmpty()) {
            boolean success = associated.stream().allMatch(
                    value -> value.state() == ClientProjectCommand.State.SUCCEEDED);
            return CompletableFuture.completedFuture(success ? Outcome.passed()
                    : Outcome.failed("命令失败、停止或结果未知，请核对命令状态"));
        }
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        barrierWorkers.submit(() -> {
            try {
                result.complete(waitForRun(principal, run));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                requestRunStop(principal.tenantId(), principal.principalId(), run.id());
                result.completeExceptionally(interrupted);
            } catch (RuntimeException failure) {
                requestRunStop(principal.tenantId(), principal.principalId(), run.id());
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    void requestRunStop(UUID tenantId, String ownerId, UUID runId) {
        ClientProjectBinding binding = bindings.run(tenantId, ownerId, runId).orElse(null);
        ClientProject project = binding == null ? null
                : projects.find(tenantId, ownerId, binding.projectId()).orElse(null);
        if (binding == null || project == null) return;
        for (ClientProjectCommand command : commands.listUnsettledForRun(tenantId, ownerId, runId)) {
            if (!Set.of(ClientProjectCommand.State.RUNNING,
                    ClientProjectCommand.State.STOP_REQUESTED,
                    ClientProjectCommand.State.QUEUED).contains(command.state())
                    || commands.hasUnresolvedTask(tenantId, ownerId, command.id())) continue;
            try {
                inTransaction(() -> {
                    ClientProjectCommand current = commands.lock(tenantId, ownerId,
                            command.id()).orElse(null);
                    if (current == null || !Set.of(ClientProjectCommand.State.RUNNING,
                            ClientProjectCommand.State.STOP_REQUESTED,
                            ClientProjectCommand.State.QUEUED).contains(current.state())) {
                        return Boolean.TRUE;
                    }
                    if (current.state() == ClientProjectCommand.State.QUEUED) {
                        tasks.closeOpenForCommand(tenantId, ownerId, current.id(),
                                clock.instant());
                        commands.update(tenantId, ownerId, current.id(), current.version(),
                                current.update(ClientProjectCommand.State.CANCELLED, true,
                                        null, false, false, "", current.sequence(),
                                        clock.instant())).orElseThrow(
                                            () -> new IllegalStateException("排队命令取消失败"));
                        return Boolean.TRUE;
                    }
                    if (!current.stopRequested()) {
                        ClientProjectCommand next = current.update(
                                ClientProjectCommand.State.STOP_REQUESTED, true,
                                current.exitCode(), current.truncated(),
                                current.outputAvailable(), current.encryptedOutput(),
                                current.sequence(), clock.instant());
                        commands.update(tenantId, ownerId, current.id(), current.version(), next)
                                .orElseThrow(() -> new IllegalStateException("停止状态无法保存"));
                    }
                    commandTask(project, binding, command.id(),
                            ClientProjectTask.Kind.STOP_COMMAND,
                            commandRequest(binding, current, "STOP_COMMAND"),
                            "CLIENT_PROJECT_COMMAND_STOP");
                    return Boolean.TRUE;
                });
            } catch (RuntimeException ignored) {
                // 取消路径不伪报停止成功；屏障到期时命令保持 UNKNOWN。
            }
        }
    }

    void completeSuccess(LocalClientDevice device, ClientProjectTask task, JsonNode output,
            Instant now) {
        if (task.kind() == ClientProjectTask.Kind.LIST_TOOLCHAINS) {
            if (output == null || !output.path("toolchains").isArray()
                    || output.path("toolchains").size() > 32
                    || output.toString().length() > MAX_COMMAND_BYTES) {
                throw new IllegalArgumentException("工具链清单无效或过大");
            }
            if (!tasks.complete(device.tenantId(), device.ownerId(), device.id(), task.id(),
                    task.version(), ClientProjectTask.State.SUCCEEDED, encrypt(output), "", now)) {
                throw new IllegalStateException("工具链清单任务终态无法保存");
            }
        } else if (task.kind() == ClientProjectTask.Kind.PLAN_COMMAND) {
            completePlan(device, task, output, now);
        } else {
            completeObservation(device, task, output, now);
        }
    }

    void completeFailure(LocalClientDevice device, ClientProjectTask task,
            ClientProjectTask.State state, String safeError, boolean possibleSideEffects,
            Instant now) {
        inTransaction(() -> {
            if (task.commandId() != null) {
                commands.lock(device.tenantId(), device.ownerId(), task.commandId())
                        .filter(value -> value.state().unsettled())
                        .ifPresent(value -> {
                            ClientProjectCommand.State next = task.kind()
                                    == ClientProjectTask.Kind.RUN_COMMAND
                                    && state == ClientProjectTask.State.FAILED
                                    && !possibleSideEffects
                                    ? ClientProjectCommand.State.FAILED
                                    : ClientProjectCommand.State.UNKNOWN;
                            commands.update(value.tenantId(), value.ownerId(), value.id(),
                                    value.version(), value.update(next,
                                            value.stopRequested(), value.exitCode(),
                                            value.truncated(), value.outputAvailable(),
                                            value.encryptedOutput(), value.sequence(), now))
                                    .orElseThrow(
                                            () -> new IllegalStateException("命令失败状态无法保存"));
                        });
            }
            if (!tasks.complete(device.tenantId(), device.ownerId(), device.id(), task.id(),
                    task.version(), state, "", safeError, now)) {
                throw new IllegalStateException("命令任务终态无法保存");
            }
            audit.appendControlled(device.tenantId(), device.ownerId(),
                    "CLIENT_PROJECT_COMMAND_TASK", "CLIENT_PROJECT_COMMAND",
                    task.commandId() == null ? task.id().toString()
                            : task.commandId().toString(), state.name(), "runId=" + task.runId());
            return Boolean.TRUE;
        });
    }

    void taskClosed(ClientProjectTask task, ClientProjectTask.State state, Instant now) {
        if (task.commandId() == null) return;
        commands.lock(task.tenantId(), task.ownerId(), task.commandId()).ifPresent(command -> {
            if (!command.state().unsettled()) return;
            ClientProjectCommand.State next = task.kind() == ClientProjectTask.Kind.RUN_COMMAND
                    && state == ClientProjectTask.State.CANCELLED
                    ? ClientProjectCommand.State.CANCELLED : ClientProjectCommand.State.UNKNOWN;
            commands.update(command.tenantId(), command.ownerId(), command.id(),
                    command.version(), command.update(next, command.stopRequested(),
                            command.exitCode(), command.truncated(), command.outputAvailable(),
                            command.encryptedOutput(), command.sequence(), now))
                    .orElseThrow(() -> new IllegalStateException("过期命令状态无法保存"));
        });
    }

    boolean hasUnsettled(UUID tenantId, String ownerId, UUID projectId) {
        return commands.hasUnsettledForProject(tenantId, ownerId, projectId);
    }

    /** @return 设备领取前命令仍处于允许执行或对账的状态 */
    boolean claimable(ClientProjectTask task) {
        if (task.commandId() == null) return true;
        ClientProjectCommand command = commands.find(task.tenantId(), task.ownerId(),
                task.commandId()).orElse(null);
        if (command == null || !command.projectId().equals(task.projectId())
                || !command.runId().equals(task.runId())) return false;
        return task.kind() == ClientProjectTask.Kind.RUN_COMMAND
                ? command.state() == ClientProjectCommand.State.QUEUED
                : command.state().unsettled();
    }

    void validateApproval(ToolApprovalDecision decision, UUID tenantId, String owner,
            String summary) {
        if (decision != ToolApprovalDecision.APPROVE) return;
        var pattern = java.util.regex.Pattern.compile(
                "^项目命令 commandId=([0-9a-f-]{36}) version=([1-9][0-9]*) digest=([0-9a-f]{64})$");
        var match = pattern.matcher(summary == null ? "" : summary);
        if (!match.matches()) throw new IllegalArgumentException("命令审批缺少可核验标识");
        ClientProjectCommand command = commands.find(tenantId, owner,
                UUID.fromString(match.group(1))).orElse(null);
        if (command == null || command.state() != ClientProjectCommand.State.PROPOSED
                || command.version() != Long.parseLong(match.group(2))
                || !command.planDigest().equals(match.group(3))
                || !command.displayExact()) {
            throw new IllegalArgumentException("命令审批版本、摘要或状态已变化");
        }
    }

    private Outcome waitForRun(PrincipalRef principal, RunRecord run)
            throws InterruptedException {
        ClientProjectBinding binding = bindings.run(principal.tenantId(),
                principal.principalId(), run.id()).orElse(null);
        if (binding == null) return Outcome.failed("命令来源缺失");
        ClientProject project = projects.find(principal.tenantId(), principal.principalId(),
                binding.projectId()).orElse(null);
        if (project == null) return Outcome.failed("命令项目不存在");
        long deadline = System.nanoTime() + properties.getCommandBarrierTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            List<ClientProjectCommand> current = commands.listUnsettledForRun(
                    principal.tenantId(), principal.principalId(), run.id());
            if (current.isEmpty()) {
                return allAssociatedSucceeded(principal, run) ? Outcome.passed()
                        : Outcome.failed("命令失败、停止或结果未知，请核对命令状态");
            }
            for (ClientProjectCommand command : current) {
                if (!commands.hasUnresolvedTask(principal.tenantId(),
                        principal.principalId(), command.id())) {
                    enqueue(principal, binding, project, command.id(),
                            ClientProjectTask.Kind.COMMAND_STATUS,
                            commandRequest(binding, command, "COMMAND_STATUS"),
                            "CLIENT_PROJECT_COMMAND_BARRIER_STATUS");
                }
            }
            Thread.sleep(WAIT_MILLIS);
        }
        Instant now = clock.instant();
        for (ClientProjectCommand command : commands.listUnsettledForRun(principal.tenantId(),
                principal.principalId(), run.id())) {
            tasks.closeOpenForCommand(principal.tenantId(), principal.principalId(),
                    command.id(), now);
        }
        // 先登记停止，再把本次屏障超时涉及的未决命令收口为 UNKNOWN。停止任务仍可被领取，
        // UNKNOWN 只解除逻辑阻断，客户端物理根隔离仍由命令状态和本地进程看护保持。
        requestRunStop(principal.tenantId(), principal.principalId(), run.id());
        Instant unknownAt = clock.instant();
        commands.expireUnsettled(principal.tenantId(), principal.principalId(), run.id(),
                unknownAt, unknownAt);
        return Outcome.failed("命令在完成屏障期限内未取得退出或未知证据，请停止后人工核对");
    }

    private boolean allAssociatedSucceeded(PrincipalRef principal, RunRecord run) {
        return commands.listForRun(principal.tenantId(), principal.principalId(), run.id())
                .stream()
                .filter(value -> value.state() != ClientProjectCommand.State.PROPOSED)
                .allMatch(value -> value.state() == ClientProjectCommand.State.SUCCEEDED);
    }

    private ClientProjectService.TaskView enqueue(PrincipalRef principal,
            ClientProjectBinding binding, ClientProject project, UUID commandId,
            ClientProjectTask.Kind kind, ObjectNode request, String action) {
        return inTransaction(() -> view(commandTask(project, binding, commandId, kind, request,
                action)));
    }

    private ClientProjectTask commandTask(ClientProject project, ClientProjectBinding binding,
            UUID commandId, ClientProjectTask.Kind kind, ObjectNode request, String action) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        ClientProjectTask task = new ClientProjectTask(id, project.tenantId(), project.ownerId(),
                project.deviceId(), project.id(), binding.runId(), binding.agentId(),
                binding.conversationId(), null, commandId, kind, encrypt(request),
                ClientProjectTask.State.PENDING, "", "", now,
                now.plus(properties.getCommandTaskTtl()), null, null, 1);
        if (!tasks.insert(task)) throw new IllegalStateException("设备项目命令任务无法登记");
        audit.appendControlled(project.tenantId(), project.ownerId(), action,
                "CLIENT_PROJECT_COMMAND",
                commandId == null ? id.toString() : commandId.toString(), "PENDING",
                "runId=" + binding.runId());
        return task;
    }

    private ObjectNode commandRequest(ClientProjectBinding binding,
            ClientProjectCommand command, String kind) {
        ClientProject project = projects.find(binding.tenantId(), binding.ownerId(),
                binding.projectId()).orElseThrow();
        return json.createObjectNode().put("kind", kind)
                .put("rootId", project.clientRootId().toString())
                .put("operationId", command.id().toString())
                .put("planDigest", command.planDigest());
    }

    private void completePlan(LocalClientDevice device, ClientProjectTask task, JsonNode output,
            Instant now) {
        if (output == null || !output.isObject() || !output.path("manifest").isObject()
                || !output.path("preview").isObject()) {
            throw new IllegalArgumentException("命令计划结果缺少清单或预览");
        }
        String planDigest = output.path("planDigest").asText("");
        String displayDigest = output.path("displayDigest").asText("");
        if (!planDigest.matches("[0-9a-f]{64}") || !displayDigest.matches("[0-9a-f]{64}")
                || !canonicalDigest(output.path("manifest")).equals(planDigest)
                || !canonicalDigest(output.path("preview")).equals(displayDigest)
                || !output.path("displayExact").asBoolean(false)) {
            throw new IllegalArgumentException("命令计划摘要或精确展示无效");
        }
        ObjectNode stored = json.createObjectNode().put("planDigest", planDigest)
                .put("displayDigest", displayDigest);
        stored.set("manifest", output.path("manifest").deepCopy());
        stored.set("preview", output.path("preview").deepCopy());
        ClientProjectCommand command = new ClientProjectCommand(task.id(), task.tenantId(),
                task.ownerId(), task.projectId(), task.agentId(), task.conversationId(),
                task.runId(), planDigest, displayDigest, true, encrypt(stored), "", false,
                false, null, ClientProjectCommand.State.PROPOSED, false, 0, 1, now, now);
        ObjectNode result = json.createObjectNode().put("operationId", command.id().toString())
                .put("version", command.version()).put("digest", command.planDigest())
                .put("displayDigest", command.displayDigest()).put("displayExact", true);
        result.set("preview", output.path("preview").deepCopy());
        inTransaction(() -> {
            if (!commands.insert(command)) throw new IllegalStateException("命令计划无法保存");
            if (!tasks.complete(device.tenantId(), device.ownerId(), device.id(), task.id(),
                    task.version(), ClientProjectTask.State.SUCCEEDED, encrypt(result), "", now)) {
                throw new IllegalStateException("命令计划任务终态无法保存");
            }
            audit.appendControlled(device.tenantId(), device.ownerId(),
                    "CLIENT_PROJECT_COMMAND_PLAN", "CLIENT_PROJECT_COMMAND",
                    command.id().toString(), "SUCCEEDED", "runId=" + task.runId());
            return Boolean.TRUE;
        });
    }

    private void completeObservation(LocalClientDevice device, ClientProjectTask task,
            JsonNode output, Instant now) {
        ClientProjectCommand command = commands.lock(device.tenantId(), device.ownerId(),
                task.commandId()).orElseThrow(() -> new IllegalArgumentException("命令不存在"));
        if (!command.projectId().equals(task.projectId()) || !command.runId().equals(task.runId())) {
            throw new IllegalArgumentException("命令任务来源不一致");
        }
        ClientProjectCommand.State observed = ClientProjectCommand.State.valueOf(
                output.path("state").asText(""));
        ClientProjectCommand.State state = command.state()
                == ClientProjectCommand.State.STOP_REQUESTED
                && (observed == ClientProjectCommand.State.RUNNING
                    || observed == ClientProjectCommand.State.STOP_REQUESTED)
                ? ClientProjectCommand.State.STOP_REQUESTED : observed;
        long sequence = output.path("sequence").asLong(-1);
        boolean available = output.path("outputAvailable").asBoolean(false);
        boolean truncated = output.path("truncated").asBoolean(false);
        Integer exit = output.path("exitCode").isInt() ? output.path("exitCode").intValue() : null;
        String stdout = decodeText(output.path("stdoutBase64").asText(""));
        String stderr = decodeText(output.path("stderrBase64").asText(""));
        String outputJson = cipher.encrypt(json.createObjectNode()
                .put("stdout", safe(stdout)).put("stderr", safe(stderr)).toString());
        boolean recoveredReceipt = (task.kind() == ClientProjectTask.Kind.COMMAND_STATUS
                || task.kind() == ClientProjectTask.Kind.STOP_COMMAND)
                && observed == ClientProjectCommand.State.UNKNOWN && sequence == 0
                && !available && exit == null && stdout.isEmpty() && stderr.isEmpty();
        if (recoveredReceipt) {
            // 重启后的私有回执不持有输出或输出序号；只证明结果未知，不能回退已收集输出或伪称进程退出。
            sequence = command.sequence();
            available = command.outputAvailable();
            truncated = command.truncated();
            exit = command.exitCode();
            outputJson = command.encryptedOutput();
            if (available) {
                JsonNode prior = decrypt(outputJson);
                stdout = prior.path("stdout").asText("");
                stderr = prior.path("stderr").asText("");
            }
        }
        if (sequence < command.sequence() || sequence > 1_000_000_000L || sequence < 0) {
            throw new IllegalArgumentException("命令输出序号无效");
        }
        boolean repeatedTerminal = Set.of(ClientProjectCommand.State.SUCCEEDED,
                        ClientProjectCommand.State.FAILED, ClientProjectCommand.State.CANCELLED)
                .contains(command.state()) && state == command.state();
        ClientProjectCommand next = repeatedTerminal ? command
                : command.update(state, command.stopRequested()
                        || output.path("stopRequested").asBoolean(false), exit, truncated,
                        available, outputJson, sequence, now);
        ObjectNode result = json.createObjectNode().put("commandId", command.id().toString())
                .put("state", next.state().name()).put("version", next.version())
                .put("sequence", next.sequence()).put("exitCode", exit)
                .put("truncated", truncated).put("outputAvailable", available)
                .put("stopRequested", next.stopRequested())
                .put("stdout", safe(stdout)).put("stderr", safe(stderr));
        inTransaction(() -> {
            if (next != command) {
                commands.update(command.tenantId(), command.ownerId(), command.id(),
                        command.version(), next).orElseThrow(
                                () -> new IllegalStateException("命令状态版本已变化"));
            }
            if (!tasks.complete(device.tenantId(), device.ownerId(), device.id(), task.id(),
                    task.version(), ClientProjectTask.State.SUCCEEDED, encrypt(result), "", now)) {
                throw new IllegalStateException("命令状态任务终态无法保存");
            }
            audit.appendControlled(device.tenantId(), device.ownerId(),
                    "CLIENT_PROJECT_COMMAND_OBSERVED", "CLIENT_PROJECT_COMMAND",
                    command.id().toString(), state.name(), "runId=" + task.runId());
            return Boolean.TRUE;
        });
    }

    private ClientProjectCommand owned(PrincipalRef principal, UUID projectId, UUID commandId) {
        ClientProjectCommand command = commands.find(principal.tenantId(),
                principal.principalId(), commandId).orElseThrow(
                        () -> new IllegalArgumentException("命令不存在或不可访问"));
        if (!command.projectId().equals(projectId)) {
            throw new IllegalArgumentException("命令不属于当前项目");
        }
        return command;
    }

    private ClientProjectBinding binding(ClientProjectCommand command) {
        return bindings.run(command.tenantId(), command.ownerId(), command.runId()).orElseThrow(
                () -> new IllegalArgumentException("命令来源绑定不存在"));
    }

    private Source source(PrincipalRef principal, ClientProjectBinding binding,
            boolean requireActiveDevice) {
        if (binding == null || !principal.tenantId().equals(binding.tenantId())
                || !principal.principalId().equals(binding.ownerId())
                || binding.protocol() != ClientProjectBinding.PROTOCOL) {
            throw new IllegalArgumentException("命令来源不属于当前主体");
        }
        ClientProject project = projects.find(principal.tenantId(), principal.principalId(),
                binding.projectId()).orElseThrow(
                        () -> new IllegalArgumentException("设备项目不存在"));
        if (project.state() != ClientProject.State.ACTIVE
                || !project.deviceId().equals(binding.deviceId())
                || !project.clientRootId().equals(binding.clientRootId())
                || !project.rootIdentity().equals(binding.rootIdentity())) {
            throw new IllegalArgumentException("设备项目来源已变化");
        }
        if (requireActiveDevice) {
            LocalClientDevice device = devices.find(principal.tenantId(),
                    principal.principalId(), project.deviceId()).orElse(null);
            if (device == null || device.state() != LocalClientDevice.State.ACTIVE) {
                throw new IllegalArgumentException("目标设备已撤销或不可用");
            }
        }
        return new Source(project);
    }

    private void require(PrincipalRef principal, String permission) {
        if (!permissions.check(principal, permission).allowed()) {
            throw new IllegalArgumentException("当前账号缺少设备项目命令权限");
        }
    }

    private CommandView commandView(ClientProjectCommand command) {
        JsonNode output = command.outputAvailable() ? decrypt(command.encryptedOutput()) : null;
        return new CommandView(command.id(), command.projectId(), command.runId(),
                command.state().name(), command.version(), command.planDigest(),
                command.displayDigest(), command.displayExact(), command.sequence(),
                command.exitCode(), command.truncated(), command.outputAvailable(),
                command.stopRequested(), output == null ? "" : output.path("stdout").asText(""),
                output == null ? "" : output.path("stderr").asText(""), command.createdAt(),
                command.updatedAt());
    }

    private JsonNode decodePlan(ClientProjectCommand command) {
        JsonNode value = decrypt(command.encryptedPlan());
        if (!canonicalDigest(value.path("manifest")).equals(command.planDigest())
                || !canonicalDigest(value.path("preview")).equals(command.displayDigest())) {
            throw new IllegalArgumentException("命令清单完整性异常");
        }
        return value;
    }

    private String canonicalDigest(JsonNode value) {
        try {
            return LocalProjectRootCipher.digest(json.copy().configure(
                    SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsString(value));
        } catch (IOException failure) {
            throw new IllegalArgumentException("命令摘要无法计算");
        }
    }

    private String decodeText(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length > MAX_COMMAND_BYTES) throw new IllegalArgumentException();
            String value = new String(decoded, StandardCharsets.UTF_8);
            java.util.Arrays.fill(decoded, (byte) 0);
            return value.replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", "");
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("命令输出编码无效");
        }
    }

    private String safe(String value) {
        String result = redactor.redact(value);
        return result.length() > MAX_COMMAND_BYTES ? result.substring(0, MAX_COMMAND_BYTES) : result;
    }

    private JsonNode decrypt(String encrypted) {
        try {
            return json.readTree(cipher.decrypt(encrypted));
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("命令密文不可用");
        }
    }

    private String encrypt(Object value) {
        try {
            return cipher.encrypt(json.writeValueAsString(value));
        } catch (IOException failure) {
            throw new IllegalStateException("命令密文无法生成");
        }
    }

    private void record(PrincipalRef principal, UUID resourceId, String action, String result) {
        audit.appendControlled(principal.tenantId(), principal.principalId(), action,
                "CLIENT_PROJECT_COMMAND", resourceId.toString(), result, "元数据审计");
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return Objects.requireNonNull(transaction.execute(status -> action.get()),
                "事务结果不能为空");
    }

    private static ClientProjectService.TaskView view(ClientProjectTask task) {
        return new ClientProjectService.TaskView(task.id(), task.kind().name(),
                task.state().name(), task.expiresAt());
    }

    @Override @PreDestroy public void close() {
        barrierWorkers.shutdownNow();
        try {
            barrierWorkers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record Source(ClientProject project) { }

    /** @param settled 命令是否收敛 @param message 安全完成或失败说明 */
    public record Outcome(boolean settled, String message) {
        static Outcome passed() { return new Outcome(true, ""); }
        static Outcome failed(String message) { return new Outcome(false, message); }
    }

    /**
     * @param commandId 命令标识
     * @param projectId 项目标识
     * @param runId Run 标识
     * @param state 权威状态
     * @param version 条件版本
     * @param planDigest 完整计划摘要
     * @param displayDigest 展示摘要
     * @param displayExact 展示是否完整
     * @param sequence 输出序号
     * @param exitCode 已确认退出码
     * @param truncated 输出是否截断
     * @param outputAvailable 是否持有输出
     * @param stopRequested 是否已登记停止
     * @param stdout 脱敏标准输出
     * @param stderr 脱敏错误输出
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record CommandView(UUID commandId, UUID projectId, UUID runId, String state,
            long version, String planDigest, String displayDigest, boolean displayExact,
            long sequence, Integer exitCode, boolean truncated, boolean outputAvailable,
            boolean stopRequested, String stdout, String stderr, Instant createdAt,
            Instant updatedAt) { }

    /**
     * @param commandId 命令标识
     * @param state 状态
     * @param version 版本
     * @param planDigest 完整计划摘要
     * @param displayDigest 展示摘要
     * @param displayExact 展示是否完整
     * @param preview 不含宿主绝对路径和基础环境值的精确审批预览
     */
    public record CommandPreview(UUID commandId, String state, long version,
            String planDigest, String displayDigest, boolean displayExact, JsonNode preview) { }
}
