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
        return new ModelConfigRepository() {
            @Override
            public ModelConfig save(ModelConfig modelConfig) {
                return store.saveModelConfig(modelConfig);
            }

            @Override
            public ModelConfig save(ModelConfig modelConfig, String encryptedApiKey) {
                return store.saveModelConfig(modelConfig, encryptedApiKey);
            }

            @Override
            public ModelConfig update(ModelConfig modelConfig) {
                return store.updateModelConfig(modelConfig);
            }

            @Override
            public ModelConfig update(ModelConfig modelConfig, String encryptedApiKey) {
                return store.updateModelConfig(modelConfig, encryptedApiKey);
            }

            @Override
            public Optional<ModelConfig> findByTenantAndId(UUID tenantId, UUID modelConfigId) {
                return store.findModelConfig(tenantId, modelConfigId);
            }

            @Override
            public Optional<String> findEncryptedApiKeyByTenantAndId(UUID tenantId, UUID modelConfigId) {
                return store.findEncryptedModelApiKey(tenantId, modelConfigId);
            }

            @Override
            public List<ModelConfig> listByTenant(UUID tenantId) {
                return store.listModelConfigs(tenantId);
            }

            @Override
            public boolean isReferencedByAgent(UUID tenantId, UUID modelConfigId) {
                return store.isModelConfigReferenced(tenantId, modelConfigId);
            }

            @Override
            public boolean delete(UUID tenantId, UUID modelConfigId) {
                return store.deleteModelConfig(tenantId, modelConfigId);
            }
        };
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
            /**
             * 保存传入的领域对象或配置，并返回当前存储快照。
             *
             * @param config 待写入内存仓储的动态 HTTP 工具配置
             */
            public com.cmagent.core.domain.HttpToolConfig save(com.cmagent.core.domain.HttpToolConfig config) {
                return store.saveHttpToolConfig(config);
            }

            @Override
            /**
             * 按租户及方法声明的标识查询匹配记录。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             * @param toolId 目标工具标识，用于定位关联的工具定义。
             */
            public Optional<com.cmagent.core.domain.HttpToolConfig> findByTenantAndToolId(UUID tenantId, UUID toolId) {
                return store.findHttpToolConfig(tenantId, toolId);
            }

            @Override
            /**
             * 按租户及方法声明的标识查询匹配记录。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             * @param toolIds 待批量查询或关联的工具标识集合。
             */
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
            /**
             * 删除当前租户边界内的目标记录或关联。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             * @param toolId 目标工具标识，用于定位关联的工具定义。
             */
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
            /**
             * 保存传入的领域对象或配置，并返回当前存储快照。
             *
             * @param publication 待保存或校验的 MCP 发布记录。
             */
            public com.cmagent.core.domain.McpToolPublication save(com.cmagent.core.domain.McpToolPublication publication) {
                return store.saveMcpToolPublication(publication);
            }

            @Override
            /**
             * 按租户及方法声明的标识查询匹配记录。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             * @param toolId 目标工具标识，用于定位关联的工具定义。
             */
            public Optional<com.cmagent.core.domain.McpToolPublication> findByTenantAndToolId(UUID tenantId, UUID toolId) {
                return store.findMcpToolPublication(tenantId, toolId);
            }

            @Override
            /**
             * 按租户及方法声明的标识查询匹配记录。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             * @param toolIds 待批量查询或关联的工具标识集合。
             */
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
            /**
             * 列出当前范围内符合条件的记录。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             */
            public List<com.cmagent.core.domain.McpToolPublication> listEnabledByTenant(UUID tenantId) {
                return store.listEnabledMcpToolPublications(tenantId);
            }

            @Override
            /**
             * 删除当前租户边界内的目标记录或关联。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             * @param toolId 目标工具标识，用于定位关联的工具定义。
             */
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
            /**
             * 保存传入的领域对象或配置，并返回当前存储快照。
             *
             * @param agent 当前处理的 Agent 定义。
             */
            public AgentDefinition save(AgentDefinition agent) {
                return store.saveAgent(agent);
            }

            @Override
            /**
             * 按租户及方法声明的标识查询匹配记录。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             * @param agentId 目标 Agent 标识，用于定位关联的 Agent 定义。
             */
            public Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
                return store.findAgent(tenantId, agentId);
            }

            @Override
            /**
             * 按租户及方法声明的条件列出匹配记录。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             */
            public List<AgentDefinition> listByTenant(UUID tenantId) {
                return store.listAgents(tenantId);
            }

            @Override
            /**
             * 增加指定目标或关联。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             * @param agentId 目标 Agent 标识，用于定位关联的 Agent 定义。
             * @param toolId 目标工具标识，用于定位关联的工具定义。
             */
            public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
                return store.addToolToAgent(tenantId, agentId, toolId);
            }

            @Override
            /**
             * 从 Agent 的关联集合移除指定工具。
             *
             * @param tenantId 当前租户标识
             * @param agentId 目标 Agent 标识
             * @param toolId 目标工具标识
             */
            public AgentDefinition removeToolFromAgent(UUID tenantId, UUID agentId, UUID toolId) {
                return store.removeToolFromAgent(tenantId, agentId, toolId);
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
            /**
             * 保存传入的领域对象或配置，并返回当前存储快照。
             *
             * @param tool 当前处理的工具定义。
             */
            public ToolDefinition save(ToolDefinition tool) {
                return store.saveTool(tool);
            }

            @Override
            /**
             * 恢复由平台管理且定义一致的本地工具。
             *
             * @param tool 当前处理的工具定义
             */
            public boolean restoreManagedLocalTool(ToolDefinition tool) {
                return store.restoreManagedLocalTool(tool);
            }

            @Override
            /**
             * 恢复删除流程中已被标记删除的工具，用于失败补偿。
             *
             * @param tool 当前处理的工具定义
             */
            public boolean restoreDeletedToolForCompensation(ToolDefinition tool) {
                return store.restoreDeletedToolForCompensation(tool);
            }

            @Override
            /**
             * 更新现有工具定义并返回最新快照。
             *
             * @param tool 当前处理的工具定义
             */
            public ToolDefinition update(ToolDefinition tool) {
                return store.updateTool(tool);
            }

            @Override
            /**
             * 按租户及方法声明的标识查询匹配记录。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             * @param toolId 目标工具标识，用于定位关联的工具定义。
             */
            public Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId) {
                return store.findTool(tenantId, toolId);
            }

            @Override
            /**
             * 按租户及方法声明的条件列出匹配记录。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             */
            public List<ToolDefinition> listByTenant(UUID tenantId) {
                return store.listTools(tenantId);
            }

            @Override
            /**
             * 判断指定工具是否已经产生调用历史。
             *
             * @param tenantId 当前租户标识
             * @param toolId 目标工具标识
             */
            public boolean hasToolCallHistory(UUID tenantId, UUID toolId) {
                return store.hasToolCallHistory(tenantId, toolId);
            }

            @Override
            /**
             * 删除当前租户边界内的目标记录或关联。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             * @param toolId 目标工具标识，用于定位关联的工具定义。
             */
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
            /**
             * 保存传入的领域对象或配置，并返回当前存储快照。
             *
             * @param grant 待保存或校验的工具授权。
             */
            public ToolGrant save(ToolGrant grant) {
                return store.saveGrant(grant);
            }

            @Override
            /**
             * 按租户及方法声明的条件列出匹配记录。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             */
            public List<ToolGrant> listByTenant(UUID tenantId) {
                return store.listGrants(tenantId);
            }

            @Override
            /**
             * 按租户及方法声明的条件列出匹配记录。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             * @param agentId 目标 Agent 标识，用于定位关联的 Agent 定义。
             */
            public List<ToolGrant> listByTenantAndAgent(UUID tenantId, UUID agentId) {
                return store.listGrants(tenantId, agentId);
            }

            @Override
            /**
             * 按租户及方法声明的条件列出匹配记录。
             *
             * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
             * @param agentId 目标 Agent 标识，用于定位关联的 Agent 定义。
             * @param toolId 目标工具标识，用于定位关联的工具定义。
             */
            public List<ToolGrant> listByTenantAgentAndTool(UUID tenantId, UUID agentId, UUID toolId) {
                return store.listGrants(tenantId, agentId, toolId);
            }

            @Override
            /**
             * 删除指定租户边界内的目标记录。
             *
             * @param tenantId 当前租户标识
             * @param agentId 目标 Agent 标识
             * @param toolId 目标工具标识
             */
            public void delete(UUID tenantId, UUID agentId, UUID toolId) {
                store.deleteGrant(tenantId, agentId, toolId);
            }

            @Override
            /**
             * 删除租户内指定工具的全部授权。
             *
             * @param tenantId 当前租户标识
             * @param toolId 目标工具标识
             */
            public void deleteByTenantAndToolId(UUID tenantId, UUID toolId) {
                store.deleteGrantsByTenantAndToolId(tenantId, toolId);
            }
        };
    }
}
