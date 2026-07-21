package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.store.InMemoryPlatformStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ManagementCommandServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private ToolDefinitionRepository toolRepository;

    @Mock
    private ToolGrantRepository grantRepository;

    @Mock
    private HttpToolConfigRepository httpToolConfigRepository;

    @Mock
    private McpToolPublicationRepository mcpToolPublicationRepository;

    @Mock
    private AuditAppender auditAppender;

    @Test
    void memoryFallbackDoesNotPersistAgentWhenAuditFails() {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        AgentDefinitionRepository agentRepository = new AgentDefinitionRepository() {
            @Override
            public AgentDefinition save(AgentDefinition agent) {
                return store.saveAgent(agent);
            }

            @Override
            public Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
                return store.findAgent(tenantId, agentId);
            }

            @Override
            public List<AgentDefinition> listByTenant(UUID tenantId) {
                return store.listAgents(tenantId);
            }

            @Override
            public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
                return store.addToolToAgent(tenantId, agentId, toolId);
            }
        };
        doThrow(new AuditPersistenceException("审计写入失败", new IllegalStateException("database unavailable")))
                .when(auditAppender)
                .append(any(), any(), any(), any(), any(), any(), any());

        ManagementCommandService service = new ManagementCommandService(
                agentRepository,
                toolRepository,
                httpToolConfigRepository,
                mcpToolPublicationRepository,
                grantRepository,
                auditAppender,
                null
        );
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("agent:write"));

        assertThatThrownBy(() -> service.createAgent(principal, "助手", "系统提示", "qwen-max"))
                .isInstanceOf(AuditPersistenceException.class);

        assertThat(store.listAgents(TENANT_ID)).isEmpty();
        verify(auditAppender).append(any(), any(), any(), any(), any(), any(), any());
    }
}
