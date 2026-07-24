package com.cmagent.server.config;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolCallRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.server.store.InMemoryPlatformStore;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Configuration
@EnableConfigurationProperties(CmAgentPersistenceProperties.class)
/** 按持久化模式选择 memory 或 JDBC Repository 实现。 */
public class ServerRepositoryConfiguration {

    private static final UUID DEFAULT_MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID DEFAULT_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * 创建内存平台存储，并植入本地 fake runtime 所需的默认模型元数据。
     *
     * @return 内存平台存储实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    public InMemoryPlatformStore inMemoryPlatformStore() {
        InMemoryPlatformStore store = new InMemoryPlatformStore();
        store.saveModelConfig(new ModelConfig(
                DEFAULT_MODEL_ID,
                DEFAULT_TENANT_ID,
                ModelProviderType.OPENAI_COMPATIBLE,
                "默认模型",
                "https://example.invalid",
                "qwen-max",
                true
        ));
        return store;
    }

    /**
     * 创建内存模型配置 Repository。
     *
     * @param store 内存平台存储
     * @return 模型配置 Repository
     */
    @Bean
    @ConditionalOnMissingBean(ModelConfigRepository.class)
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    public ModelConfigRepository memoryModelConfigRepository(InMemoryPlatformStore store) {
        return store::findModelConfig;
    }

    /**
     * 创建内存运行记录 Repository。
     *
     * @param store 内存平台存储
     * @return 运行记录 Repository
     */
    @Bean
    @ConditionalOnMissingBean(RunRepository.class)
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    public RunRepository memoryRunRepository(InMemoryPlatformStore store) {
        return store;
    }

    /**
     * 创建内存工具调用记录 Repository。
     *
     * @param store 内存平台存储
     * @return 工具调用 Repository
     */
    @Bean
    @ConditionalOnMissingBean(ToolCallRepository.class)
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    public ToolCallRepository memoryToolCallRepository(InMemoryPlatformStore store) {
        return store;
    }

    /**
     * 创建内存 HTTP 工具配置 Repository，并保持所有查询按租户隔离。
     *
     * @param store 内存平台存储
     * @return HTTP 工具配置 Repository
     */
    @Bean
    @ConditionalOnMissingBean(HttpToolConfigRepository.class)
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    public HttpToolConfigRepository memoryHttpToolConfigRepository(InMemoryPlatformStore store) {
        return new HttpToolConfigRepository() {
            @Override
            public com.cmagent.core.domain.HttpToolConfig save(com.cmagent.core.domain.HttpToolConfig config) {
                return store.saveHttpToolConfig(config);
            }

            @Override
            public Optional<com.cmagent.core.domain.HttpToolConfig> findByTenantAndToolId(UUID tenantId, UUID toolId) {
                return store.findHttpToolConfig(tenantId, toolId);
            }

            @Override
            public Map<UUID, com.cmagent.core.domain.HttpToolConfig> findByTenantAndToolIds(
                    UUID tenantId, List<UUID> toolIds
            ) {
                return toolIds.stream()
                        .map(toolId -> store.findHttpToolConfig(tenantId, toolId))
                        .flatMap(Optional::stream)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                com.cmagent.core.domain.HttpToolConfig::toolId,
                                config -> config
                        ));
            }

            @Override
            public void delete(UUID tenantId, UUID toolId) {
                store.deleteHttpToolConfig(tenantId, toolId);
            }
        };
    }

    /**
     * 创建内存 MCP 发布 Repository，并保持发布记录按租户隔离。
     *
     * @param store 内存平台存储
     * @return MCP 工具发布 Repository
     */
    @Bean
    @ConditionalOnMissingBean(McpToolPublicationRepository.class)
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    public McpToolPublicationRepository memoryMcpToolPublicationRepository(InMemoryPlatformStore store) {
        return new McpToolPublicationRepository() {
            @Override
            public com.cmagent.core.domain.McpToolPublication save(com.cmagent.core.domain.McpToolPublication publication) {
                return store.saveMcpToolPublication(publication);
            }

            @Override
            public Optional<com.cmagent.core.domain.McpToolPublication> findByTenantAndToolId(UUID tenantId, UUID toolId) {
                return store.findMcpToolPublication(tenantId, toolId);
            }

            @Override
            public Map<UUID, com.cmagent.core.domain.McpToolPublication> findByTenantAndToolIds(
                    UUID tenantId, List<UUID> toolIds
            ) {
                return toolIds.stream()
                        .map(toolId -> store.findMcpToolPublication(tenantId, toolId))
                        .flatMap(Optional::stream)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                com.cmagent.core.domain.McpToolPublication::toolId,
                                publication -> publication
                        ));
            }

            @Override
            public List<com.cmagent.core.domain.McpToolPublication> listEnabledByTenant(UUID tenantId) {
                return store.listEnabledMcpToolPublications(tenantId);
            }

            @Override
            public void delete(UUID tenantId, UUID toolId) {
                store.deleteMcpToolPublication(tenantId, toolId);
            }
        };
    }

    /**
     * 创建持久化配置启动校验器。
     *
     * @param properties  持久化配置属性
     * @param environment Spring 环境及当前激活 profile
     * @return Spring 初始化回调
     */
    @Bean
    public InitializingBean cmAgentPersistenceValidator(
            CmAgentPersistenceProperties properties,
            Environment environment
    ) {
        return () -> properties.validate(environment);
    }

    /**
     * 创建内存 Agent 定义 Repository。
     *
     * @param store 内存平台存储
     * @return Agent 定义 Repository
     */
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

    /**
     * 创建内存工具定义 Repository。
     *
     * @param store 内存平台存储
     * @return 工具定义 Repository
     */
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

            @Override
            public void delete(UUID tenantId, UUID toolId) {
                store.deleteTool(tenantId, toolId);
            }
        };
    }

    /**
     * 创建内存工具授权 Repository。
     *
     * @param store 内存平台存储
     * @return 工具授权 Repository
     */
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
