package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.repository.ConversationRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.WorkspaceSelectionRepository;
import com.cmagent.core.runtime.WorkspaceContextException;
import com.cmagent.server.audit.AuditAppender;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkspaceImportAccessClientProjectTest {
    @Test void 设备项目会话和Run交由专用来源闸门而不使用普通资料可见性() {
        var tenant = UUID.randomUUID();
        var principal = new PrincipalRef(tenant, "owner", "测试", Set.of());
        var conversation = new Conversation(UUID.randomUUID(), tenant, UUID.randomUUID(),
                "设备项目", "owner", Instant.now(), Instant.now());
        var run = RunRecord.create(UUID.randomUUID(), tenant, conversation.agentId(),
                "owner", "", Instant.now());
        var conversations = mock(ConversationRepository.class);
        var runs = mock(RunRepository.class);
        var selections = mock(WorkspaceSelectionRepository.class);
        var client = mock(ClientProjectRunService.class);
        var access = new WorkspaceImportAccess(selections, conversations, runs, mock(AuditAppender.class));
        access.configureClientProjects(new StaticListableBeanFactory(Map.of("client", client))
                .getBeanProvider(ClientProjectRunService.class));
        when(conversations.workspaceProtocol(tenant, conversation.agentId(), conversation.id())).thenReturn(5);
        when(runs.workspaceProtocol(tenant, run.agentId(), run.id())).thenReturn(5);

        access.conversation(principal, conversation);
        access.run(principal, run);

        verify(client).verifyConversation(principal, conversation);
        verify(client).verifyResource(principal, run);
        verifyNoInteractions(selections);
    }

    @Test void 来源头降级但仍有设备绑定时拒绝而不回退普通会话() {
        var tenant = UUID.randomUUID();
        var principal = new PrincipalRef(tenant, "owner", "测试", Set.of());
        var conversation = new Conversation(UUID.randomUUID(), tenant, UUID.randomUUID(),
                "设备项目", "owner", Instant.now(), Instant.now());
        var conversations = mock(ConversationRepository.class);
        var selections = mock(WorkspaceSelectionRepository.class);
        var client = mock(ClientProjectRunService.class);
        var access = new WorkspaceImportAccess(selections, conversations, mock(RunRepository.class), mock(AuditAppender.class));
        access.configureClientProjects(new StaticListableBeanFactory(Map.of("client", client))
                .getBeanProvider(ClientProjectRunService.class));
        when(conversations.hasClientProjectSource(tenant, conversation.agentId(), conversation.id())).thenReturn(true);
        doThrow(new LocalProjectAccessException("CLIENT_PROJECT_BINDING_CORRUPT", "error-fixture", 409,
                "设备项目会话来源见证损坏")).when(client).verifyConversation(principal, conversation);

        assertThatThrownBy(() -> access.conversation(principal, conversation))
                .isInstanceOf(LocalProjectAccessException.class)
                .hasMessage("设备项目会话来源见证损坏");
        verifyNoInteractions(selections);
    }

    @Test void 功能关闭与跨租户均拒绝且保留严格拒绝审计() {
        var tenant = UUID.randomUUID();
        var principal = new PrincipalRef(tenant, "owner", "测试", Set.of());
        var conversation = new Conversation(UUID.randomUUID(), tenant, UUID.randomUUID(),
                "设备项目", "owner", Instant.now(), Instant.now());
        var conversations = mock(ConversationRepository.class);
        var audit = mock(AuditAppender.class);
        var access = new WorkspaceImportAccess(mock(WorkspaceSelectionRepository.class), conversations,
                mock(RunRepository.class), audit);
        when(conversations.workspaceProtocol(tenant, conversation.agentId(), conversation.id())).thenReturn(5);

        assertThatThrownBy(() -> access.conversation(principal, conversation))
                .isInstanceOf(WorkspaceContextException.class).hasMessage("请求资源不存在");
        assertThatThrownBy(() -> access.conversation(
                new PrincipalRef(UUID.randomUUID(), "owner", "测试", Set.of()), conversation))
                .isInstanceOf(WorkspaceContextException.class).hasMessage("请求资源不存在");
        verify(audit, times(2)).appendControlled(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void 设备会话中的Run缺失见证时不能经历史接口回退() {
        var tenant = UUID.randomUUID();
        var principal = new PrincipalRef(tenant, "owner", "测试", Set.of());
        var conversation = new Conversation(UUID.randomUUID(), tenant, UUID.randomUUID(),
                "设备项目", "owner", Instant.now(), Instant.now());
        var run = RunRecord.create(UUID.randomUUID(), tenant, conversation.agentId(), "owner", "", Instant.now());
        var conversations = mock(ConversationRepository.class);
        var selections = mock(WorkspaceSelectionRepository.class);
        var access = new WorkspaceImportAccess(selections, conversations, mock(RunRepository.class), mock(AuditAppender.class));
        when(conversations.findByRun(tenant, run.id())).thenReturn(Optional.of(conversation));
        when(conversations.hasClientProjectSource(tenant, conversation.agentId(), conversation.id())).thenReturn(true);

        assertThatThrownBy(() -> access.run(principal, run))
                .isInstanceOf(WorkspaceContextException.class).hasMessage("请求资源不存在");
        verifyNoInteractions(selections);
    }
}
