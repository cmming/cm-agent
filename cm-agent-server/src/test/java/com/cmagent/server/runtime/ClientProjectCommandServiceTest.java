package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ClientProject;
import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.ClientProjectCommand;
import com.cmagent.core.domain.ClientProjectTask;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.repository.ClientProjectBindingRepository;
import com.cmagent.core.repository.ClientProjectCommandRepository;
import com.cmagent.core.repository.ClientProjectRepository;
import com.cmagent.core.repository.ClientProjectOperationRepository;
import com.cmagent.core.repository.ClientProjectTaskRepository;
import com.cmagent.core.repository.LocalClientDeviceRepository;
import com.cmagent.core.security.DefaultPermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.ClientProjectProperties;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientProjectCommandServiceTest {
    private final UUID tenant = UUID.randomUUID();
    private final String owner = "owner";
    private final ClientProjectCommandRepository commands =
            mock(ClientProjectCommandRepository.class);
    private final ClientProjectTaskRepository tasks = mock(ClientProjectTaskRepository.class);
    private final ClientProjectBindingRepository bindings =
            mock(ClientProjectBindingRepository.class);
    private final ClientProjectRepository projects = mock(ClientProjectRepository.class);
    private final Instant now = Instant.parse("2026-11-10T00:00:00Z");

    @Test void 命令审批只接受原提案精确版本摘要() {
        UUID commandId = UUID.randomUUID();
        String digest = "a".repeat(64);
        ClientProjectCommand command = command(commandId, digest, 1,
                ClientProjectCommand.State.PROPOSED, now);
        when(commands.find(tenant, owner, commandId)).thenReturn(Optional.of(command));
        var service = service();

        assertThatCode(() -> service.validateApproval(com.cmagent.core.domain.ToolApprovalDecision.APPROVE,
                tenant, owner, "项目命令 commandId=" + commandId
                        + " version=1 digest=" + digest)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validateApproval(
                com.cmagent.core.domain.ToolApprovalDecision.APPROVE, tenant, owner,
                "项目命令 commandId=" + commandId + " version=2 digest=" + digest))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void 模型结束但命令未知时Run屏障不通过并收口为未知() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        ClientProjectBinding binding = new ClientProjectBinding(tenant, owner, projectId,
                agentId, conversationId, runId, UUID.randomUUID(), UUID.randomUUID(),
                "b".repeat(64), 1, now);
        ClientProject project = new ClientProject(projectId, tenant, owner,
                binding.deviceId(), binding.clientRootId(), binding.rootIdentity(), "项目",
                ClientProject.State.ACTIVE, 1, now, now);
        ClientProjectCommand running = command(UUID.randomUUID(), "a".repeat(64), 3,
                ClientProjectCommand.State.RUNNING, now);
        when(bindings.run(tenant, owner, runId)).thenReturn(Optional.of(binding));
        when(projects.find(tenant, owner, projectId)).thenReturn(Optional.of(project));
        when(commands.listForRun(tenant, owner, runId)).thenReturn(List.of(running));
        when(commands.listUnsettledForRun(tenant, owner, runId)).thenReturn(List.of(running));
        when(tasks.insert(any())).thenReturn(true);
        when(commands.hasUnresolvedTask(tenant, owner, running.id())).thenReturn(false);
        when(commands.lock(tenant, owner, running.id())).thenReturn(Optional.of(running));
        when(commands.update(eq(tenant), eq(owner), eq(running.id()), eq(3L), any()))
                .thenAnswer(call -> Optional.of(call.getArgument(4)));
        var properties = new ClientProjectProperties();
        properties.setCommandBarrierTimeout(java.time.Duration.ofMillis(2));
        var service = service(properties);

        var outcome = service.awaitRunSettled(new PrincipalRef(tenant, owner, "测试",
                Set.of("project:read", "project:execute", "agent:run")),
                new RunRecord(runId, tenant, agentId, owner, com.cmagent.core.domain.RunKind.NORMAL,
                        com.cmagent.core.domain.RunStatus.RUNNING, "输入", "", "",
                        now, null)).get();

        assertThat(outcome.settled()).isFalse();
        assertThat(outcome.message()).contains("完成屏障期限");
        verify(commands).expireUnsettled(tenant, owner, runId, now, now);
        ArgumentCaptor<ClientProjectTask> queued = ArgumentCaptor.forClass(ClientProjectTask.class);
        verify(tasks, atLeastOnce()).insert(queued.capture());
        assertThat(queued.getAllValues())
                .anyMatch(task -> task.kind() == ClientProjectTask.Kind.STOP_COMMAND);
    }

    @Test void 仅提议未启动的命令不阻断Run成功() throws Exception {
        UUID runId = UUID.randomUUID();
        ClientProjectCommand proposed = command(UUID.randomUUID(), "a".repeat(64), 1,
                ClientProjectCommand.State.PROPOSED, now);
        when(commands.listForRun(tenant, owner, runId)).thenReturn(List.of(proposed));

        var outcome = service().awaitRunSettled(new PrincipalRef(tenant, owner, "测试",
                Set.of("project:read", "project:execute", "agent:run")),
                new RunRecord(runId, tenant, UUID.randomUUID(), owner,
                        com.cmagent.core.domain.RunKind.NORMAL,
                        com.cmagent.core.domain.RunStatus.RUNNING, "输入", "", "",
                        now, null)).get();

        assertThat(outcome.settled()).isTrue();
    }

    @Test void 已执行命令失败时Run屏障不通过() throws Exception {
        UUID runId = UUID.randomUUID();
        ClientProjectCommand failed = command(UUID.randomUUID(), "a".repeat(64), 4,
                ClientProjectCommand.State.FAILED, now);
        when(commands.listForRun(tenant, owner, runId)).thenReturn(List.of(failed));

        var outcome = service().awaitRunSettled(new PrincipalRef(tenant, owner, "测试",
                Set.of("project:read", "project:execute", "agent:run")),
                new RunRecord(runId, tenant, UUID.randomUUID(), owner,
                        com.cmagent.core.domain.RunKind.NORMAL,
                        com.cmagent.core.domain.RunStatus.RUNNING, "输入", "", "",
                        now, null)).get();

        assertThat(outcome.settled()).isFalse();
    }

    @Test void 重复停止只转入状态核对而不生成第二个停止任务() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        String digest = "a".repeat(64);
        ClientProjectBinding binding = new ClientProjectBinding(tenant, owner, projectId,
                UUID.randomUUID(), UUID.randomUUID(), runId, deviceId, rootId,
                "b".repeat(64), 1, now);
        ClientProject project = new ClientProject(projectId, tenant, owner, deviceId, rootId,
                binding.rootIdentity(), "项目", ClientProject.State.ACTIVE, 1, now, now);
        ClientProjectCommand stopping = new ClientProjectCommand(commandId, tenant, owner,
                projectId, binding.agentId(), binding.conversationId(), runId, digest,
                "c".repeat(64), true, "encrypted-plan", "", false, false, null,
                ClientProjectCommand.State.STOP_REQUESTED, true, 0, 3, now, now);
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        LocalClientDevice device = new LocalClientDevice(deviceId, tenant, owner, "设备",
                LocalClientDevice.Platform.WINDOWS,
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
                LocalClientDevice.State.ACTIVE, 1, now, now);
        when(commands.find(tenant, owner, commandId)).thenReturn(Optional.of(stopping));
        when(commands.lock(tenant, owner, commandId)).thenReturn(Optional.of(stopping));
        when(bindings.run(tenant, owner, runId)).thenReturn(Optional.of(binding));
        when(projects.find(tenant, owner, projectId)).thenReturn(Optional.of(project));
        when(tasks.insert(any())).thenReturn(true);
        LocalClientDeviceRepository devices = mock(LocalClientDeviceRepository.class);
        when(devices.find(tenant, owner, deviceId)).thenReturn(Optional.of(device));

        var task = service(devices).startStop(new PrincipalRef(tenant, owner, "测试",
                Set.of("project:read", "project:execute")), projectId, commandId);

        assertThat(task.kind()).isEqualTo("COMMAND_STATUS");
    }

    @Test void 启动前已确认失败的命令收口为FAILED而不是UNKNOWN() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        ClientProjectCommand queued = new ClientProjectCommand(commandId, tenant, owner,
                projectId, agentId, conversationId, runId, "a".repeat(64),
                "c".repeat(64), true, "encrypted-plan", "", false, false, null,
                ClientProjectCommand.State.QUEUED, false, 0, 2, now, now);
        ClientProjectTask launched = new ClientProjectTask(UUID.randomUUID(), tenant, owner,
                deviceId, projectId, runId, agentId, conversationId, null, commandId,
                ClientProjectTask.Kind.RUN_COMMAND, "encrypted-request",
                ClientProjectTask.State.DISPATCHED, "", "", now, now.plusSeconds(60),
                now, null, 2);
        when(commands.lock(tenant, owner, commandId)).thenReturn(Optional.of(queued));
        when(commands.update(eq(tenant), eq(owner), eq(commandId), eq(2L), any()))
                .thenAnswer(call -> Optional.of(call.getArgument(4)));
        when(tasks.complete(tenant, owner, deviceId, launched.id(), 2,
                ClientProjectTask.State.FAILED, "", "工具链不可用", now)).thenReturn(true);
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        LocalClientDevice device = new LocalClientDevice(deviceId, tenant, owner, "设备",
                LocalClientDevice.Platform.WINDOWS,
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
                LocalClientDevice.State.ACTIVE, 1, now, now);

        service().completeFailure(device, launched, ClientProjectTask.State.FAILED,
                "工具链不可用", false, now);

        ArgumentCaptor<ClientProjectCommand> updated =
                ArgumentCaptor.forClass(ClientProjectCommand.class);
        verify(commands).update(eq(tenant), eq(owner), eq(commandId), eq(2L), updated.capture());
        assertThat(updated.getValue().state()).isEqualTo(ClientProjectCommand.State.FAILED);
    }

    @Test void 设备返回精确命令计划后原子保存计划与任务终态() throws Exception {
        UUID id = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        ClientProjectTask planning = new ClientProjectTask(id, tenant, owner, deviceId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, ClientProjectTask.Kind.PLAN_COMMAND, "encrypted-request",
                ClientProjectTask.State.DISPATCHED, "", "", now, now.plusSeconds(60),
                now, null, 2);
        var mapper = new ObjectMapper();
        ObjectNode manifest = mapper.createObjectNode().put("version", 1)
                .put("toolchainId", "java");
        manifest.putArray("arguments").add("test");
        ObjectNode preview = mapper.createObjectNode().put("toolchainId", "java")
                .put("displayName", "Java");
        ObjectNode output = mapper.createObjectNode()
                .put("planDigest", canonicalDigest(mapper, manifest))
                .put("displayDigest", canonicalDigest(mapper, preview))
                .put("displayExact", true);
        output.set("manifest", manifest);
        output.set("preview", preview);
        when(commands.insert(any())).thenReturn(true);
        when(tasks.complete(any(), any(), any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(true);
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        LocalClientDevice device = new LocalClientDevice(deviceId, tenant, owner, "设备",
                LocalClientDevice.Platform.WINDOWS,
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
                LocalClientDevice.State.ACTIVE, 1, now, now);

        service().completeSuccess(device, planning, output, now);

        verify(commands).insert(any(ClientProjectCommand.class));
        verify(tasks).complete(any(), any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test void 设备退出证据原子推进命令与状态任务终态() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        ClientProjectCommand running = new ClientProjectCommand(commandId, tenant, owner,
                projectId, agentId, conversationId, runId, "a".repeat(64),
                "c".repeat(64), true, "encrypted-plan", "", false, false, null,
                ClientProjectCommand.State.RUNNING, false, 0, 3, now, now);
        UUID taskId = UUID.randomUUID();
        ClientProjectTask observing = new ClientProjectTask(taskId, tenant, owner, deviceId,
                projectId, runId, agentId, conversationId, null, commandId,
                ClientProjectTask.Kind.COMMAND_STATUS, "encrypted-request",
                ClientProjectTask.State.DISPATCHED, "", "", now, now.plusSeconds(60),
                now, null, 2);
        when(commands.lock(tenant, owner, commandId)).thenReturn(Optional.of(running));
        when(commands.update(eq(tenant), eq(owner), eq(commandId), eq(3L), any()))
                .thenAnswer(call -> Optional.of(call.getArgument(4)));
        when(tasks.complete(any(), any(), any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(true);
        var payload = new ObjectMapper().createObjectNode().put("state", "SUCCEEDED")
                .put("sequence", 1).put("exitCode", 0).put("truncated", false)
                .put("outputAvailable", true).put("stopRequested", false)
                .put("stdoutBase64", "").put("stderrBase64", "");
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        LocalClientDevice device = new LocalClientDevice(deviceId, tenant, owner, "设备",
                LocalClientDevice.Platform.WINDOWS,
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
                LocalClientDevice.State.ACTIVE, 1, now, now);

        service().completeSuccess(device, observing, payload, now);

        verify(commands).update(eq(tenant), eq(owner), eq(commandId), eq(3L), any());
        verify(tasks).complete(any(), any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test void 已终态命令可重复查询但不重复推进命令版本() throws Exception {
        UUID commandId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        ClientProjectCommand completed = new ClientProjectCommand(commandId, tenant, owner,
                projectId, agentId, conversationId, runId, "a".repeat(64),
                "c".repeat(64), true, "encrypted-plan", "", false, false, 0,
                ClientProjectCommand.State.SUCCEEDED, false, 1, 4, now, now);
        ClientProjectTask observing = new ClientProjectTask(UUID.randomUUID(), tenant, owner,
                deviceId, projectId, runId, agentId, conversationId, null, commandId,
                ClientProjectTask.Kind.COMMAND_STATUS, "encrypted-request",
                ClientProjectTask.State.DISPATCHED, "", "", now, now.plusSeconds(60),
                now, null, 2);
        when(commands.lock(tenant, owner, commandId)).thenReturn(Optional.of(completed));
        when(tasks.complete(any(), any(), any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(true);
        var payload = new ObjectMapper().createObjectNode().put("state", "SUCCEEDED")
                .put("sequence", 1).put("exitCode", 0).put("truncated", false)
                .put("outputAvailable", false).put("stopRequested", false)
                .put("stdoutBase64", "").put("stderrBase64", "");
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        LocalClientDevice device = new LocalClientDevice(deviceId, tenant, owner, "设备",
                LocalClientDevice.Platform.WINDOWS,
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
                LocalClientDevice.State.ACTIVE, 1, now, now);

        service().completeSuccess(device, observing, payload, now);

        verify(commands, never()).update(eq(tenant), eq(owner), eq(commandId), anyLong(), any());
        verify(tasks).complete(any(), any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    private ClientProjectCommandService service() {
        return service(new ClientProjectProperties());
    }

    private ClientProjectCommandService service(ClientProjectProperties properties) {
        return service(properties, mock(LocalClientDeviceRepository.class));
    }

    private ClientProjectCommandService service(LocalClientDeviceRepository devices) {
        return service(new ClientProjectProperties(), devices);
    }

    private ClientProjectCommandService service(ClientProjectProperties properties,
            LocalClientDeviceRepository devices) {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(
                        mock(org.springframework.transaction.TransactionStatus.class)));
        var cipher = new ModelCredentialCipher(
                new javax.crypto.spec.SecretKeySpec(new byte[32], "AES"));
        return new ClientProjectCommandService(commands,
                mock(ClientProjectOperationRepository.class), tasks, bindings, projects,
                devices, new DefaultPermissionEvaluator(),
                mock(AuditAppender.class), cipher,
                new SensitiveDataRedactor(), new ObjectMapper(), transaction, properties,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private ClientProjectCommand command(UUID id, String digest, long version,
            ClientProjectCommand.State state, Instant time) {
        return new ClientProjectCommand(id, tenant, owner, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), digest, "c".repeat(64), true,
                "encrypted-plan", "", false, false, null, state, false, 0, version, time, time);
    }

    private static String canonicalDigest(ObjectMapper mapper, Object value) throws Exception {
        byte[] bytes = mapper.copy().configure(
                SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsBytes(value);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
