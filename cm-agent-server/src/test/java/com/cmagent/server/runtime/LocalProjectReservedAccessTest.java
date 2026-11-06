package com.cmagent.server.runtime;

import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.WorkspaceContextException;
import com.cmagent.server.audit.AuditAppender;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class LocalProjectReservedAccessTest {
    @Test void 头降级为旧来源或导入仍由持久项目见证统一拒绝且不泄露正文(CapturedOutput output) {
        for (int protocol : new int[]{0, 3}) {
            UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), id = UUID.randomUUID();
            var principal = new PrincipalRef(tenant, "Owner", "测试", Set.of("agent:read"));
            var value = new Conversation(id, tenant, agent, "PRIVATE_DOWNGRADED_TITLE", "Owner", Instant.now(), Instant.now());
            var conversations = mock(ConversationRepository.class);
            when(conversations.workspaceProtocol(tenant, agent, id)).thenReturn(protocol);
            when(conversations.hasLocalProjectSource(tenant, agent, id)).thenReturn(true);
            var runs = mock(RunRepository.class);
            var run = RunRecord.create(UUID.randomUUID(), tenant, agent, "Owner", "PRIVATE_DOWNGRADED_INPUT", Instant.now());
            when(runs.workspaceProtocol(tenant, agent, run.id())).thenReturn(protocol);
            when(runs.hasLocalProjectSource(tenant, agent, run.id())).thenReturn(true);
            var audit = mock(AuditAppender.class);
            var gate = new WorkspaceImportAccess(mock(WorkspaceSelectionRepository.class), conversations, runs, audit);
            var conversationFailure = catchThrowableOfType(() -> gate.conversation(principal, value), WorkspaceContextException.class);
            var runFailure = catchThrowableOfType(() -> gate.run(principal, run), WorkspaceContextException.class);
            assertThat(conversationFailure.code()).isEqualTo(ApiErrorCode.WORKSPACE_RESOURCE_NOT_FOUND);
            assertThat(runFailure.code()).isEqualTo(ApiErrorCode.WORKSPACE_RESOURCE_NOT_FOUND);
            assertThat(output).contains("errorId=" + conversationFailure.errorId(), "errorId=" + runFailure.errorId())
                    .doesNotContain("PRIVATE_DOWNGRADED_TITLE", "PRIVATE_DOWNGRADED_INPUT");
            verify(audit, times(2)).appendControlled(eq(tenant), eq("Owner"), eq("WORKSPACE_IMPORT_ACCESS"),
                    eq("WORKSPACE_RESOURCE"), anyString(), eq("DENIED"), anyString());
        }
    }

    @Test void 项目功能开启也必须由项目闸门拒绝头与绑定不一致() {
        UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), id = UUID.randomUUID();
        var principal = new PrincipalRef(tenant, "Owner", "测试", Set.of("project:read"));
        var conversation = new Conversation(id, tenant, agent, "合成私有标题", "Owner", Instant.now(), Instant.now());
        var binding = new LocalProjectBinding(tenant, "Owner", UUID.randomUUID(), agent, id, null,
                UUID.randomUUID(), "a".repeat(64), "local-node", 1, Instant.now());
        var conversations = mock(ConversationRepository.class);
        when(conversations.hasLocalProjectSource(tenant, agent, id)).thenReturn(true);
        when(conversations.workspaceProtocol(tenant, agent, id)).thenReturn(0);
        var bindings = mock(LocalProjectBindingRepository.class);
        when(bindings.conversation(tenant, "Owner", id)).thenReturn(Optional.of(binding));
        var access = mock(LocalProjectAccessService.class);
        var expected = new LocalProjectAccessException("PROJECT_RESOURCE_NOT_FOUND", UUID.randomUUID().toString(), 404, "项目资源不存在或不可访问");
        when(access.reject(eq(principal), eq(id), eq("PROJECT_RESOURCE_NOT_FOUND"), eq(404), anyString())).thenReturn(expected);
        var resources = new LocalProjectResourceAccess(bindings, conversations, mock(RunRepository.class), access, mock(LocalProjectRequestBoundary.class));
        @SuppressWarnings("unchecked")
        var provider = (org.springframework.beans.factory.ObjectProvider<LocalProjectResourceAccess>) mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resources);
        var gate = new WorkspaceImportAccess(mock(WorkspaceSelectionRepository.class), conversations, mock(RunRepository.class), mock(AuditAppender.class));
        gate.configureProjects(provider);
        assertThatThrownBy(() -> gate.conversation(principal, conversation)).isSameAs(expected);
        verify(access, never()).project(any(), any(), any());
    }

    @Test void 头降级的项目Run不能从旧资料准备入口读取检查点(CapturedOutput output) {
        for (int protocol : new int[]{0, 3}) {
            UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID();
            var run = RunRecord.create(UUID.randomUUID(), tenant, agent, "Owner", "PRIVATE_DOWNGRADED_INPUT", Instant.now());
            var principal = new PrincipalRef(tenant, "Owner", "测试", Set.of("agent:run"));
            var selections = mock(WorkspaceSelectionService.class);
            when(selections.protocol(run)).thenReturn(protocol);
            when(selections.hasLocalProjectSource(run)).thenReturn(true);
            var checkpoints = mock(RuntimeCheckpointRepository.class);
            var service = new WorkspaceContextService(new com.cmagent.server.config.WorkspaceContextProperties(), checkpoints,
                    null, new com.fasterxml.jackson.databind.ObjectMapper(), mock(AuditAppender.class),
                    new com.cmagent.server.config.AgentScopeRuntimeProperties());
            service.configureSelections(selections);
            var failure = catchThrowableOfType(() -> service.prepare(principal, run, false), WorkspaceContextException.class);
            assertThat(failure.code()).isEqualTo(ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
            assertThat(failure.errorId()).isEqualTo(run.id().toString());
            verifyNoInteractions(checkpoints);
            assertThat(output).contains("errorId=" + failure.errorId()).doesNotContain("PRIVATE_DOWNGRADED_INPUT");
        }
    }

    @Test void 项目会话协议在专用授权接线前统一拒绝并记录同号日志(CapturedOutput output) {
        UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), id = UUID.randomUUID();
        var principal = new PrincipalRef(tenant, "Owner", "测试", Set.of("agent:read"));
        var value = new Conversation(id, tenant, agent, "PRIVATE_PROJECT_TITLE", "Owner", Instant.now(), Instant.now());
        var conversations = mock(ConversationRepository.class);
        when(conversations.workspaceProtocol(tenant, agent, id)).thenReturn(LocalProjectBinding.PROTOCOL);
        var gate = new WorkspaceImportAccess(mock(WorkspaceSelectionRepository.class), conversations, mock(RunRepository.class), mock(AuditAppender.class));
        var failure = catchThrowableOfType(() -> gate.conversation(principal, value), WorkspaceContextException.class);
        assertThat(failure.code()).isEqualTo(ApiErrorCode.WORKSPACE_RESOURCE_NOT_FOUND);
        assertThat(failure.errorId()).isNotBlank();
        assertThat(output).contains("errorId=" + failure.errorId()).doesNotContain("PRIVATE_PROJECT_TITLE");
        var response = new com.cmagent.server.web.ApiExceptionHandler().workspaceFailure(failure,
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/agents/test/conversations/" + id));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo(ApiErrorCode.CONVERSATION_NOT_FOUND);
        assertThat(response.getBody().errorId()).isEqualTo(failure.errorId());
    }

    @Test void 项目运行协议不能经独立详情旁路(CapturedOutput output) {
        UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), id = UUID.randomUUID();
        var principal = new PrincipalRef(tenant, "Owner", "测试", Set.of("agent:read"));
        var value = RunRecord.create(id, tenant, agent, "Owner", "PRIVATE_PROJECT_INPUT", Instant.now());
        var runs = mock(RunRepository.class);
        when(runs.workspaceProtocol(tenant, agent, id)).thenReturn(LocalProjectBinding.PROTOCOL);
        var gate = new WorkspaceImportAccess(mock(WorkspaceSelectionRepository.class), mock(ConversationRepository.class), runs, mock(AuditAppender.class));
        var failure = catchThrowableOfType(() -> gate.run(principal, value), WorkspaceContextException.class);
        assertThat(failure.code()).isEqualTo(ApiErrorCode.WORKSPACE_RESOURCE_NOT_FOUND);
        assertThat(output).contains("errorId=" + failure.errorId()).doesNotContain("PRIVATE_PROJECT_INPUT");
    }

    @Test void 派生自项目会话的旧标记运行也不能旁路() {
        UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), id = UUID.randomUUID(), conversationId = UUID.randomUUID();
        var principal = new PrincipalRef(tenant, "Owner", "测试", Set.of("agent:read"));
        var value = RunRecord.create(id, tenant, agent, "Owner", "合成", Instant.now());
        var conversation = new Conversation(conversationId, tenant, agent, "私有", "Owner", Instant.now(), Instant.now());
        var conversations = mock(ConversationRepository.class);
        when(conversations.findByRun(tenant, id)).thenReturn(Optional.of(conversation));
        when(conversations.workspaceProtocol(tenant, agent, conversationId)).thenReturn(LocalProjectBinding.PROTOCOL);
        var gate = new WorkspaceImportAccess(mock(WorkspaceSelectionRepository.class), conversations, mock(RunRepository.class), mock(AuditAppender.class));
        assertThatThrownBy(() -> gate.run(principal, value)).isInstanceOf(WorkspaceContextException.class);
    }

    @Test void 项目运行在Runtime接线前不能按旧参考目录执行(CapturedOutput output) {
        UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), id = UUID.randomUUID();
        var principal = new PrincipalRef(tenant, "Owner", "测试", Set.of("agent:run"));
        var value = RunRecord.create(id, tenant, agent, "Owner", "PRIVATE_PROJECT_INPUT", Instant.now());
        var selections = mock(WorkspaceSelectionService.class);
        when(selections.protocol(value)).thenReturn(LocalProjectBinding.PROTOCOL);
        var checkpoints = mock(RuntimeCheckpointRepository.class);
        // 故意不提供加密器：新来源必须在触碰任何旧正文/加密状态之前明确拒绝。
        var service = new WorkspaceContextService(new com.cmagent.server.config.WorkspaceContextProperties(), checkpoints,
                null, new com.fasterxml.jackson.databind.ObjectMapper(), mock(AuditAppender.class),
                new com.cmagent.server.config.AgentScopeRuntimeProperties());
        service.configureSelections(selections);
        var failure = catchThrowableOfType(() -> service.prepare(principal, value, false), WorkspaceContextException.class);
        assertThat(failure.code()).isEqualTo(ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE);
        assertThat(failure.errorId()).isEqualTo(id.toString());
        verifyNoInteractions(checkpoints);
        assertThat(output).contains("errorId=" + id).doesNotContain("PRIVATE_PROJECT_INPUT");
    }
}

