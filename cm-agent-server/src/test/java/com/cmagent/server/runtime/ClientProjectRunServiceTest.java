package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ClientProject;
import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.Conversation;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ClientProjectBindingRepository;
import com.cmagent.core.repository.ClientProjectRepository;
import com.cmagent.core.repository.ConversationRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.core.security.DefaultPermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientProjectRunServiceTest {
    @Test void 新会话响应丢失后可按原请求编号恢复而不重复创建() {
        UUID tenant = UUID.randomUUID(), projectId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID(), requestId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-09T00:00:00Z");
        var principal = new PrincipalRef(tenant, "owner", "测试",
                Set.of("project:read", "project:manage", "agent:read", "agent:run",
                        "tool:grant"));
        var projects = mock(ClientProjectRepository.class);
        var bindings = mock(ClientProjectBindingRepository.class);
        var agents = mock(AgentDefinitionRepository.class);
        var conversations = mock(ConversationRepository.class);
        var project = new ClientProject(projectId, tenant, "owner", UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), "项目", ClientProject.State.ACTIVE,
                1, now, now);
        var binding = new ClientProjectBinding(tenant, "owner", projectId, agentId,
                requestId, null, project.deviceId(), project.clientRootId(),
                project.rootIdentity(), 1, now);
        var conversation = new Conversation(requestId, tenant, agentId, "会话", "owner",
                now, now);
        when(projects.find(tenant, "owner", projectId)).thenReturn(Optional.of(project));
        when(agents.findByTenantAndId(tenant, agentId))
                .thenReturn(Optional.of(mock(com.cmagent.core.domain.AgentDefinition.class)));
        when(bindings.conversation(tenant, "owner", requestId)).thenReturn(Optional.of(binding));
        when(conversations.findByTenantAndAgentAndId(tenant, agentId, requestId))
                .thenReturn(Optional.of(conversation));
        var transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(invocation ->
                ((org.springframework.transaction.support.TransactionCallback<?>)
                        invocation.getArgument(0)).doInTransaction(
                                mock(org.springframework.transaction.TransactionStatus.class)));
        var service = new ClientProjectRunService(mock(RunRepository.class), projects, bindings,
                agents, conversations, mock(ToolGrantRepository.class),
                mock(ClientProjectToolCatalog.class), new DefaultPermissionEvaluator(),
                mock(AuditAppender.class), transaction, java.time.Clock.fixed(now,
                        java.time.ZoneOffset.UTC),
                mock(ClientProjectReadSnapshots.class));

        Conversation restored = service.createConversation(principal, projectId, agentId,
                requestId);

        assertThat(restored.id()).isEqualTo(requestId);
        org.mockito.Mockito.verify(conversations, org.mockito.Mockito.never())
                .save(any(), any());
    }
}
