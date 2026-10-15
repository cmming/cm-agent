package com.cmagent.server.service;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ModelConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AgentDefinitionQueryServiceTest {
    @Test
    void 按配置标识解析名称并批量查询且停用配置仍显示当前名称() {
        UUID tenantId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        AgentDefinition first = agent(tenantId, modelId);
        AgentDefinition second = agent(tenantId, UUID.randomUUID());
        var agents = mock(AgentDefinitionRepository.class);
        var models = mock(ModelConfigRepository.class);
        when(agents.listByTenant(tenantId)).thenReturn(List.of(first, second));
        when(models.listByTenant(tenantId)).thenReturn(List.of(new ModelConfig(modelId, tenantId,
                ModelProviderType.OPENAI_COMPATIBLE, "同名配置", "https://example.test/v1", "current-model", false)));

        var result = new AgentDefinitionQueryService(agents, models).list(tenantId);

        assertThat(result).containsExactly(first.withModelName("current-model"), second);
        assertThat(first.modelName()).isEqualTo("old-model");
        verify(models).listByTenant(tenantId);
        verify(agents).listByTenant(tenantId);
        verifyNoMoreInteractions(agents, models);
    }

    @Test
    void 缺失模型绑定时保留Agent且仅查询其自身租户() {
        UUID tenantId = UUID.randomUUID();
        AgentDefinition agent = agent(tenantId, UUID.randomUUID());
        var agents = mock(AgentDefinitionRepository.class);
        var models = mock(ModelConfigRepository.class);
        when(agents.findByTenantAndId(tenantId, agent.id())).thenReturn(Optional.of(agent));
        when(models.findByTenantAndId(tenantId, agent.modelProviderId())).thenReturn(Optional.empty());

        assertThat(new AgentDefinitionQueryService(agents, models).get(tenantId, agent.id())).contains(agent);

        verify(models).findByTenantAndId(tenantId, agent.modelProviderId());
        verify(agents).findByTenantAndId(tenantId, agent.id());
        verifyNoMoreInteractions(agents, models);
    }

    @Test
    void 不存在的Agent不会读取模型配置() {
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        var agents = mock(AgentDefinitionRepository.class);
        var models = mock(ModelConfigRepository.class);
        when(agents.findByTenantAndId(tenantId, agentId)).thenReturn(Optional.empty());

        assertThat(new AgentDefinitionQueryService(agents, models).get(tenantId, agentId)).isEmpty();

        verifyNoMoreInteractions(models);
    }

    private AgentDefinition agent(UUID tenantId, UUID modelId) {
        return new AgentDefinition(UUID.randomUUID(), tenantId, "测试 Agent", "描述", "帮助用户", modelId,
                "old-model", 0.4, 8, true, List.of(UUID.randomUUID()), "creator", "updater");
    }
}
