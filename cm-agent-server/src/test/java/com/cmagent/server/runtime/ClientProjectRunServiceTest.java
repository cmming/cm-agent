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
    @Test void 通用消息和Run入口仍要求设备项目专用请求头() {
        var properties = new com.cmagent.server.config.ClientProjectProperties();
        properties.setEnabled(true);
        properties.setAllowedOrigins(java.util.List.of("http://127.0.0.1:8080"));
        var boundary = new ClientProjectRequestBoundary(properties);
        var service = new ClientProjectRunService(mock(RunRepository.class), mock(ClientProjectRepository.class),
                mock(ClientProjectBindingRepository.class), mock(AgentDefinitionRepository.class),
                mock(ConversationRepository.class), mock(ToolGrantRepository.class), mock(ClientProjectToolCatalog.class),
                new DefaultPermissionEvaluator(), mock(AuditAppender.class), mock(TransactionTemplate.class),
                java.time.Clock.systemUTC(), mock(ClientProjectReadSnapshots.class));
        service.configureBoundary(new org.springframework.beans.factory.support.StaticListableBeanFactory(
                java.util.Map.of("boundary", boundary)).getBeanProvider(ClientProjectRequestBoundary.class));
        UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID();
        var principal = new PrincipalRef(tenant, "owner", "测试", Set.of());
        var conversation = new Conversation(UUID.randomUUID(), tenant, agent, "设备项目", "owner",
                Instant.now(), Instant.now());
        var run = com.cmagent.core.domain.RunRecord.create(UUID.randomUUID(), tenant, agent, "owner", "", Instant.now());
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setLocalAddr("127.0.0.1");
        request.addHeader("Host", "127.0.0.1:8080");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.verifyConversation(principal, conversation))
                    .isInstanceOfSatisfying(LocalProjectAccessException.class, failure -> {
                        assertThat(failure.status()).isEqualTo(403);
                        assertThat(failure.errorCode()).isEqualTo("CLIENT_PROJECT_REQUEST_REQUIRED");
                        assertThat(failure.errorId()).isNotBlank();
                    });
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.verifyResource(principal, run))
                    .isInstanceOf(LocalProjectAccessException.class);
            request.addHeader("X-CM-Agent-Client-Project", "1");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.verifyConversation(principal, conversation))
                    .isInstanceOfSatisfying(LocalProjectAccessException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo("CLIENT_PROJECT_BINDING_CORRUPT"));
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

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
