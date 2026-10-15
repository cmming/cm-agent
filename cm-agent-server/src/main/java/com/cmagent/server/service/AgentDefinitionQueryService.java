package com.cmagent.server.service;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ModelConfigRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 按租户组合 Agent 与当前模型配置；历史复制的模型名称不作为控制台的当前值。 */
@Service
public class AgentDefinitionQueryService {
    /** Agent 定义仓储，所有读取均受可信租户上下文限制。 */
    private final AgentDefinitionRepository agents;
    /** 模型配置仓储，仅用于读取元数据，不读取或返回凭据。 */
    private final ModelConfigRepository models;

    /**
     * 创建无缓存的查询服务，使下一次查询立即反映模型配置变化。
     *
     * @param agents Agent 定义仓储
     * @param models 模型配置仓储
     */
    public AgentDefinitionQueryService(AgentDefinitionRepository agents, ModelConfigRepository models) {
        this.agents = agents;
        this.models = models;
    }

    /**
     * 查询当前模型名称已解析的 Agent 列表，不回写历史名称。
     *
     * @param tenantId 认证主体提供的可信租户，不能来自请求体
     * @return 当前租户 Agent 的不可变列表
     */
    public List<AgentDefinition> list(UUID tenantId) {
        List<AgentDefinition> definitions = agents.listByTenant(tenantId);
        if (definitions.isEmpty()) return definitions;
        // 批量读取同租户元数据，避免每个 Agent 都查询一次；停用配置仍应显示当前名称。
        Map<UUID, ModelConfig> configs = models.listByTenant(tenantId).stream()
                .collect(Collectors.toMap(ModelConfig::id, Function.identity()));
        return definitions.stream().map(agent -> withCurrentModel(agent, configs.get(agent.modelProviderId()))).toList();
    }

    /**
     * 查询单个 Agent 并解析其当前绑定模型名称。
     *
     * @param tenantId 认证主体提供的可信租户
     * @param agentId Agent 标识，仅在当前租户内查找
     * @return 不存在时为空；绑定缺失时保留原定义供管理，运行入口仍拒绝不可用配置
     */
    public Optional<AgentDefinition> get(UUID tenantId, UUID agentId) {
        return agents.findByTenantAndId(tenantId, agentId).map(agent -> withCurrentModel(agent,
                models.findByTenantAndId(tenantId, agent.modelProviderId()).orElse(null)));
    }

    private AgentDefinition withCurrentModel(AgentDefinition agent, ModelConfig config) {
        // 只替换响应副本，既有历史与审计不变；缺失绑定不能借用其他租户的同名或同 ID 配置。
        return config == null ? agent : agent.withModelName(config.modelName());
    }
}
