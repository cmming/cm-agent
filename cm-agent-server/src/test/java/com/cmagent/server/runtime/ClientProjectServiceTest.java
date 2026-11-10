package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ClientProject;
import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.ClientProjectOperation;
import com.cmagent.core.domain.ClientProjectTask;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.repository.ClientProjectRepository;
import com.cmagent.core.repository.ClientProjectOperationRepository;
import com.cmagent.core.repository.ClientProjectTaskRepository;
import com.cmagent.core.repository.LocalClientDeviceRepository;
import com.cmagent.core.security.DefaultPermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.ClientProjectProperties;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientProjectServiceTest {
    private final UUID tenant = UUID.randomUUID();
    private final PrincipalRef principal = new PrincipalRef(tenant, "owner", "测试",
            Set.of("project:read", "project:write", "project:manage"));
    private final ClientProjectRepository projects = mock(ClientProjectRepository.class);
    private final ClientProjectOperationRepository operations =
            mock(ClientProjectOperationRepository.class);
    private final ClientProjectTaskRepository tasks = mock(ClientProjectTaskRepository.class);
    private final LocalClientDeviceRepository devices = mock(LocalClientDeviceRepository.class);
    private final AuditAppender audit = mock(AuditAppender.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final Instant now = Instant.parse("2026-11-09T00:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    private final ModelCredentialCipher cipher = new ModelCredentialCipher(
            new SecretKeySpec(new byte[32], "AES"));
    private ClientProjectService service;
    private LocalClientDevice device;

    @BeforeEach void setup() throws Exception {
        when(transaction.execute(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(
                        mock(org.springframework.transaction.TransactionStatus.class)));
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        device = new LocalClientDevice(UUID.randomUUID(), tenant, principal.principalId(),
                "开发电脑", LocalClientDevice.Platform.WINDOWS,
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                LocalClientDevice.State.ACTIVE, 1, now, now);
        when(devices.find(tenant, principal.principalId(), device.id()))
                .thenReturn(Optional.of(device));
        var properties = new ClientProjectProperties();
        properties.setEnabled(true);
        service = new ClientProjectService(projects, operations, tasks, devices,
                new DefaultPermissionEvaluator(), audit, mock(ErrorDiagnosticLogger.class),
                cipher, new SensitiveDataRedactor(), json, transaction, properties,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void 选择任务不向浏览器暴露路径或根标识且可被设备领取() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(tasks.findForOwner(tenant, principal.principalId(), requestId))
                .thenReturn(Optional.empty());
        when(tasks.insertSelectionExclusive(any())).thenReturn(true);
        var handle = service.startSelection(principal, requestId, device.id(), "项目");
        assertThat(handle.requestId()).isEqualTo(requestId);
        assertThat(handle.toString()).doesNotContain("path");

        ArgumentCaptor<ClientProjectTask> queued = ArgumentCaptor.forClass(ClientProjectTask.class);
        verify(tasks).insertSelectionExclusive(queued.capture());
        var dispatched = new ClientProjectTask(queued.getValue().id(), tenant,
                principal.principalId(), device.id(), null, null, null, null, null,
                ClientProjectTask.Kind.SELECT_ROOT,
                queued.getValue().encryptedRequest(), ClientProjectTask.State.DISPATCHED, "", "",
                now, now.plusSeconds(60), now, null, 2);
        when(tasks.claimNext(tenant, principal.principalId(), device.id(), now))
                .thenReturn(Optional.of(dispatched));
        var claimed = service.claim(device).orElseThrow();
        assertThat(claimed.category()).isEqualTo("PROJECT_SELECT_ROOT");
        assertThat(claimed.request().path("kind").asText()).isEqualTo("SELECT_ROOT");
        assertThat(claimed.request().path("expiresAt").asText()).isEqualTo(now.plusSeconds(60).toString());
        assertThat(claimed.request().toString()).doesNotContain("C:\\\\");
    }

    @Test void 目录身份成功回报后同事务创建项目并幂等推进任务() throws Exception {
        UUID taskId = UUID.randomUUID(), clientRootId = UUID.randomUUID();
        String rootIdentity = "a".repeat(64);
        var request = json.createObjectNode().put("kind", "SELECT_ROOT");
        var task = new ClientProjectTask(taskId, tenant, principal.principalId(), device.id(),
                null, null, null, null, null, ClientProjectTask.Kind.SELECT_ROOT,
                cipher.encrypt(request.toString()),
                ClientProjectTask.State.DISPATCHED, "", "", now, now.plusSeconds(60),
                now, null, 2);
        when(tasks.find(tenant, principal.principalId(), device.id(), taskId))
                .thenReturn(Optional.of(task));
        when(projects.findByRoot(tenant, principal.principalId(), device.id(), rootIdentity))
                .thenReturn(Optional.empty());
        when(projects.insert(any())).thenReturn(true);
        when(tasks.complete(eq(tenant), eq(principal.principalId()), eq(device.id()),
                eq(taskId), eq(2L), eq(ClientProjectTask.State.SUCCEEDED), any(), eq(""),
                eq(now))).thenReturn(true);
        var output = json.createObjectNode().put("rootId", clientRootId.toString())
                .put("rootIdentity", rootIdentity).put("displayName", "项目");
        byte[] body = json.writeValueAsBytes(new ResultPayload(
                ClientProjectTask.State.SUCCEEDED, 2, output, ""));

        service.complete(device, taskId, body);

        ArgumentCaptor<ClientProject> project = ArgumentCaptor.forClass(ClientProject.class);
        verify(projects).insert(project.capture());
        assertThat(project.getValue().clientRootId()).isEqualTo(clientRootId);
        assertThat(project.getValue().rootIdentity()).isEqualTo(rootIdentity);
    }

    @Test void 设备变更计划按原始摘要登记且脱敏掩盖差异时禁止批准() throws Exception {
        UUID taskId = UUID.randomUUID(), projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID(), agentId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String before = "password=old-secret";
        String after = "password=new-secret";
        String beforeSha = "a".repeat(64), afterSha = "b".repeat(64);
        var request = json.createObjectNode().put("kind", "PLAN_CHANGE")
                .put("rootId", "root");
        var task = new ClientProjectTask(taskId, tenant, principal.principalId(), device.id(),
                projectId, runId, agentId, conversationId, null,
                ClientProjectTask.Kind.PLAN_CHANGE, cipher.encrypt(request.toString()),
                ClientProjectTask.State.DISPATCHED, "", "", now, now.plusSeconds(60),
                now, null, 2);
        when(tasks.find(tenant, principal.principalId(), device.id(), taskId))
                .thenReturn(Optional.of(task));
        when(operations.insert(any())).thenReturn(true);
        when(tasks.complete(eq(tenant), eq(principal.principalId()), eq(device.id()),
                eq(taskId), eq(2L), eq(ClientProjectTask.State.SUCCEEDED), any(), eq(""),
                eq(now))).thenReturn(true);
        var output = json.createObjectNode();
        var change = output.putArray("changes").addObject().put("kind", "REPLACE_FILE")
                .put("path", "README.md").put("beforeSha256", beforeSha)
                .put("beforeIdentity", "identity").put("afterSha256", afterSha)
                .put("before", before).put("after", after);
        output.put("planDigest", planDigest(change));
        byte[] body = json.writeValueAsBytes(new ResultPayload(
                ClientProjectTask.State.SUCCEEDED, 2, output, ""));

        service.complete(device, taskId, body);

        ArgumentCaptor<ClientProjectOperation> operation =
                ArgumentCaptor.forClass(ClientProjectOperation.class);
        verify(operations).insert(operation.capture());
        assertThat(operation.getValue().planDigest()).isEqualTo(output.path("planDigest").asText());
        assertThat(operation.getValue().displayExact()).isFalse();
    }

    @Test void 列表查询忽略未使用的空搜索词而搜索仍要求非空() {
        UUID requestId = UUID.randomUUID(), projectId = UUID.randomUUID();
        ClientProject project = new ClientProject(projectId, tenant, principal.principalId(),
                device.id(), UUID.randomUUID(), "a".repeat(64), "项目",
                ClientProject.State.ACTIVE, 1, now, now);
        when(projects.find(tenant, principal.principalId(), projectId))
                .thenReturn(Optional.of(project));
        when(tasks.findForOwner(tenant, principal.principalId(), requestId))
                .thenReturn(Optional.empty());
        when(tasks.insert(any())).thenReturn(true);

        var view = service.startQuery(principal, projectId, requestId,
                ClientProjectTask.Kind.LIST,
                new ClientProjectService.ReadRequest("", "", 100, 0, 65_536, "", false));

        assertThat(view.kind()).isEqualTo("LIST");
        verify(tasks).insert(any());
        assertThatThrownBy(() -> service.startQuery(principal, projectId, UUID.randomUUID(),
                ClientProjectTask.Kind.SEARCH,
                new ClientProjectService.ReadRequest("", "", 100, 0, 65_536, "", false)))
                .hasMessageContaining("搜索词不能为空");
    }

    @Test void 应用许可仅在原提案版本摘要匹配时登记一次() {
        UUID operationId = UUID.randomUUID(), projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID(), agentId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        ClientProject project = new ClientProject(projectId, tenant, principal.principalId(),
                device.id(), UUID.randomUUID(), "a".repeat(64), "项目",
                ClientProject.State.ACTIVE, 1, now, now);
        var storedPlan = json.createObjectNode().put("rootId", "root");
        storedPlan.putArray("changes").addObject().put("kind", "CREATE_FILE")
                .put("path", "README.md").put("beforeSha256", "")
                .put("beforeIdentity", "").put("afterSha256", "b".repeat(64))
                .putNull("before").put("after", "正文");
        String digest = planDigest(storedPlan.path("changes").get(0));
        ClientProjectOperation operation = new ClientProjectOperation(operationId, tenant,
                principal.principalId(), projectId, agentId, conversationId, runId, null,
                digest, "c".repeat(64), true, cipher.encrypt(storedPlan.toString()),
                ClientProjectOperation.State.PROPOSED, 1, now, now);
        when(projects.lock(tenant, principal.principalId(), projectId))
                .thenReturn(Optional.of(project));
        when(operations.lock(tenant, principal.principalId(), operationId))
                .thenReturn(Optional.of(operation));
        when(operations.hasUncertain(tenant, principal.principalId(), projectId))
                .thenReturn(false);
        when(operations.advance(eq(tenant), eq(principal.principalId()), eq(operationId),
                eq(1L), eq(ClientProjectOperation.State.APPLYING), eq(now)))
                .thenReturn(Optional.of(operation.advance(
                        ClientProjectOperation.State.APPLYING, now)));
        when(tasks.insert(any())).thenReturn(true);
        ClientProjectBinding binding = new ClientProjectBinding(tenant,
                principal.principalId(), projectId, agentId, conversationId, runId,
                device.id(), project.clientRootId(), project.rootIdentity(), 1, now);

        var task = service.startApply(principal, binding, operationId, 1, digest);

        assertThat(task.kind()).isEqualTo("APPLY_CHANGE");
        verify(tasks).insert(any());
    }

    private String planDigest(com.fasterxml.jackson.databind.JsonNode change) {
        String value = "cm-agent/client-project/change/v1\n"
                + change.path("kind").asText() + '\0'
                + change.path("path").asText() + '\0'
                + change.path("target").asText(null) + '\0'
                + change.path("beforeSha256").asText(null) + '\0'
                + change.path("before").asText(null) + '\0'
                + change.path("afterSha256").asText(null) + '\0'
                + change.path("after").asText(null) + '\n';
        return LocalProjectRootCipher.digest(value);
    }

    private record ResultPayload(ClientProjectTask.State state, long expectedVersion,
            com.fasterxml.jackson.databind.JsonNode output, String error) { }
}
