package com.cmagent.server.config;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolCallRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.server.store.InMemoryPlatformStore;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Configuration
@EnableConfigurationProperties(CmAgentPersistenceProperties.class)
public class ServerRepositoryConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    public InMemoryPlatformStore inMemoryPlatformStore() {
        return new InMemoryPlatformStore();
    }

    @Bean
    @ConditionalOnMissingBean(RunRepository.class)
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    public RunRepository memoryRunRepository(InMemoryPlatformStore store) {
        return store;
    }

    @Bean
    @ConditionalOnMissingBean(ToolCallRepository.class)
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    public ToolCallRepository memoryToolCallRepository(InMemoryPlatformStore store) {
        return store;
    }

    @Bean
    public InitializingBean cmAgentPersistenceValidator(
            CmAgentPersistenceProperties properties,
            Environment environment
    ) {
        return () -> properties.validate(environment);
    }

    @Bean
    @ConditionalOnMissingBean(AgentDefinitionRepository.class)
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    public AgentDefinitionRepository memoryAgentDefinitionRepository(InMemoryPlatformStore store) {
        return new AgentDefinitionRepository() {
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
    }

    @Bean
    @ConditionalOnMissingBean(ToolDefinitionRepository.class)
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    public ToolDefinitionRepository memoryToolDefinitionRepository(InMemoryPlatformStore store) {
        return new ToolDefinitionRepository() {
            @Override
            public ToolDefinition save(ToolDefinition tool) {
                return store.saveTool(tool);
            }

            @Override
            public Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId) {
                return store.findTool(tenantId, toolId);
            }

            @Override
            public List<ToolDefinition> listByTenant(UUID tenantId) {
                return store.listTools(tenantId);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(ToolGrantRepository.class)
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    public ToolGrantRepository memoryToolGrantRepository(InMemoryPlatformStore store) {
        return new ToolGrantRepository() {
            @Override
            public ToolGrant save(ToolGrant grant) {
                return store.saveGrant(grant);
            }

            @Override
            public List<ToolGrant> listByTenant(UUID tenantId) {
                return store.listGrants(tenantId);
            }

            @Override
            public List<ToolGrant> listByTenantAndAgent(UUID tenantId, UUID agentId) {
                return store.listGrants(tenantId, agentId);
            }

            @Override
            public List<ToolGrant> listByTenantAgentAndTool(UUID tenantId, UUID agentId, UUID toolId) {
                return store.listGrants(tenantId, agentId, toolId);
            }
        };
    }
}
