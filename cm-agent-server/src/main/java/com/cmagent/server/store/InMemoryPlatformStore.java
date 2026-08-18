package com.cmagent.server.store;

import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditPageRequest;
import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.RunPageRequest;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.RunToolCall;
import com.cmagent.core.domain.RunToolCallBatch;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.McpToolPublication;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.ToolCallRepository;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地和测试使用的内存存储实现，不作为生产持久化方案。
 */
public class InMemoryPlatformStore implements AuditEventRepository, RunRepository, ToolCallRepository {

    private final ConcurrentHashMap<UUID, AgentDefinition> agents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ModelConfig> modelConfigs = new ConcurrentHashMap<>();
    // 内存模式同样只保留密文，保证测试和本地行为不会弱化生产安全边界。
    private final ConcurrentHashMap<UUID, String> encryptedModelApiKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> deletedToolIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<TenantToolName, UUID> toolIdsByTenantAndName = new ConcurrentHashMap<>();
    private final Object toolLock = new Object();
    private final List<ToolGrant> grants = Collections.synchronizedList(new ArrayList<>());
    private final List<AuditEvent> auditEvents = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentHashMap<UUID, RunRecord> runs = new ConcurrentHashMap<>();
    private final List<RunToolCall> toolCalls = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentHashMap<String, HttpToolConfig> httpToolConfigs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, McpToolPublication> mcpToolPublications = new ConcurrentHashMap<>();
    /**
     * 保存传入的领域对象或配置，并返回当前存储快照。
     *
     * @param config 待保存的动态 HTTP 工具配置
     */
    public HttpToolConfig saveHttpToolConfig(HttpToolConfig config) {
        httpToolConfigs.put(toolKey(config.tenantId(), config.toolId()), config);
        return config;
    }
    /**
     * 按租户和工具标识查询动态 HTTP 工具配置，并再次校验键值归属。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public Optional<HttpToolConfig> findHttpToolConfig(UUID tenantId, UUID toolId) {
        return Optional.ofNullable(httpToolConfigs.get(toolKey(tenantId, toolId)))
                .filter(config -> tenantId.equals(config.tenantId()) && toolId.equals(config.toolId()));
    }
    /**
     * 删除当前租户边界内的目标记录或关联。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public void deleteHttpToolConfig(UUID tenantId, UUID toolId) {
        httpToolConfigs.remove(toolKey(tenantId, toolId));
    }
    /**
     * 保存传入的领域对象或配置，并返回当前存储快照。
     *
     * @param publication 待保存或校验的 MCP 发布记录。
     */
    public McpToolPublication saveMcpToolPublication(McpToolPublication publication) {
        mcpToolPublications.put(toolKey(publication.tenantId(), publication.toolId()), publication);
        return publication;
    }
    /**
     * 按租户和工具标识查询 MCP 发布记录，并再次校验键值归属。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public Optional<McpToolPublication> findMcpToolPublication(UUID tenantId, UUID toolId) {
        return Optional.ofNullable(mcpToolPublications.get(toolKey(tenantId, toolId)))
                .filter(publication -> tenantId.equals(publication.tenantId()) && toolId.equals(publication.toolId()));
    }
    /**
     * 删除当前租户边界内的目标记录或关联。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public void deleteMcpToolPublication(UUID tenantId, UUID toolId) {
        mcpToolPublications.remove(toolKey(tenantId, toolId));
    }
    /**
     * 列出当前范围内符合条件的记录。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     */
    public List<McpToolPublication> listEnabledMcpToolPublications(UUID tenantId) {
        return mcpToolPublications.values().stream()
                .filter(publication -> tenantId.equals(publication.tenantId()) && publication.enabled())
                .sorted(Comparator.comparing(publication -> publication.toolId().toString()))
                .toList();
    }

    /**
     * 组合租户和工具 ID 形成内存映射键。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    private static String toolKey(UUID tenantId, UUID toolId) {
        return tenantId + ":" + toolId;
    }

    /**
     * 创建 {@code TenantToolName} 实例并保存其运行所需依赖。
     */
    private record TenantToolName(UUID tenantId, String name) {
    }
    /**
     * 保存传入的领域对象或配置，并返回当前存储快照。
     *
     * @param modelConfig 待保存的模型提供商配置
     */
    public ModelConfig saveModelConfig(ModelConfig modelConfig) {
        modelConfigs.put(modelConfig.id(), modelConfig);
        return modelConfig;
    }

    /** 保存模型配置及其 API Key 密文。 */
    public ModelConfig saveModelConfig(ModelConfig modelConfig, String encryptedApiKey) {
        ModelConfig saved = saveModelConfig(modelConfig);
        encryptedModelApiKeys.put(modelConfig.id(), encryptedApiKey);
        return saved;
    }

    /**
     * 更新租户内已有模型配置，防止把同 ID 的跨租户对象覆盖进当前存储。
     *
     * @param modelConfig 待更新的模型配置
     * @return 更新后的配置
     */
    public ModelConfig updateModelConfig(ModelConfig modelConfig) {
        AtomicReference<ModelConfig> updated = new AtomicReference<>();
        modelConfigs.computeIfPresent(modelConfig.id(), (id, existing) -> {
            if (!existing.tenantId().equals(modelConfig.tenantId())) {
                return existing;
            }
            updated.set(modelConfig);
            return modelConfig;
        });
        if (updated.get() == null) {
            throw new NoSuchElementException("模型配置不存在");
        }
        return updated.get();
    }

    /** 更新模型配置并在提供新密文时轮换 API Key。 */
    public ModelConfig updateModelConfig(ModelConfig modelConfig, String encryptedApiKey) {
        ModelConfig updated = updateModelConfig(modelConfig);
        if (encryptedApiKey != null) {
            encryptedModelApiKeys.put(modelConfig.id(), encryptedApiKey);
        }
        return updated;
    }

    /** 按租户读取模型 API Key 密文。 */
    public Optional<String> findEncryptedModelApiKey(UUID tenantId, UUID modelConfigId) {
        return findModelConfig(tenantId, modelConfigId)
                .map(ignored -> encryptedModelApiKeys.get(modelConfigId));
    }
    /**
     * 按租户和配置标识查询模型配置，防止返回跨租户数据。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param modelConfigId 模型配置标识，用于定位目标模型配置。
     */
    public Optional<ModelConfig> findModelConfig(UUID tenantId, UUID modelConfigId) {
        ModelConfig modelConfig = modelConfigs.get(modelConfigId);
        if (modelConfig == null || !tenantId.equals(modelConfig.tenantId())) {
            return Optional.empty();
        }
        return Optional.of(modelConfig);
    }

    /**
     * 按名称和 ID 稳定列出当前租户模型配置。
     *
     * @param tenantId 当前租户标识
     * @return 当前租户配置列表
     */
    public List<ModelConfig> listModelConfigs(UUID tenantId) {
        return modelConfigs.values().stream()
                .filter(modelConfig -> tenantId.equals(modelConfig.tenantId()))
                .sorted(Comparator.comparing(ModelConfig::displayName)
                        .thenComparing(modelConfig -> modelConfig.id().toString()))
                .toList();
    }

    /**
     * 判断同租户 Agent 是否仍引用目标模型配置。
     */
    public boolean isModelConfigReferenced(UUID tenantId, UUID modelConfigId) {
        return agents.values().stream().anyMatch(agent -> tenantId.equals(agent.tenantId())
                && modelConfigId.equals(agent.modelProviderId()));
    }

    /**
     * 删除租户内目标模型配置。
     *
     * @return 实际删除记录时为 {@code true}
     */
    public boolean deleteModelConfig(UUID tenantId, UUID modelConfigId) {
        Optional<ModelConfig> existing = findModelConfig(tenantId, modelConfigId);
        boolean deleted = existing.isPresent() && modelConfigs.remove(modelConfigId, existing.get());
        if (deleted) {
            encryptedModelApiKeys.remove(modelConfigId);
        }
        return deleted;
    }
    /**
     * 保存传入的领域对象或配置，并返回当前存储快照。
     *
     * @param agent 当前处理的 Agent 定义。
     */
    public AgentDefinition saveAgent(AgentDefinition agent) {
        agents.put(agent.id(), agent);
        return agent;
    }
    /**
     * 按租户和 Agent 标识查询定义，防止返回跨租户数据。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param agentId 目标 Agent 标识，用于定位关联的 Agent 定义。
     */
    public Optional<AgentDefinition> findAgent(UUID tenantId, UUID agentId) {
        AgentDefinition agent = agents.get(agentId);
        if (agent == null || !agent.tenantId().equals(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(agent);
    }
    /**
     * 列出当前范围内符合条件的记录。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     */
    public List<AgentDefinition> listAgents(UUID tenantId) {
        return agents.values().stream()
                .filter(agent -> agent.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(AgentDefinition::name, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(agent -> agent.id().toString()))
                .toList();
    }
    /**
     * 增加指定目标或关联。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param agentId 目标 Agent 标识，用于定位关联的 Agent 定义。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
        AtomicReference<AgentDefinition> updated = new AtomicReference<>();
        agents.computeIfPresent(agentId, (id, agent) -> {
            if (!agent.tenantId().equals(tenantId)) {
                throw new NoSuchElementException("Agent 不存在");
            }
            if (agent.toolIds().contains(toolId)) {
                updated.set(agent);
                return agent;
            }
            List<UUID> toolIds = new ArrayList<>(agent.toolIds());
            toolIds.add(toolId);
            AgentDefinition merged = new AgentDefinition(
                    agent.id(),
                    agent.tenantId(),
                    agent.name(),
                    agent.description(),
                    agent.systemPrompt(),
                    agent.modelProviderId(),
                    agent.modelName(),
                    agent.temperature(),
                    agent.maxIterations(),
                    agent.enabled(),
                    toolIds,
                    agent.createdBy(),
                    agent.updatedBy()
            );
            updated.set(merged);
            return merged;
        });
        AgentDefinition result = updated.get();
        if (result == null) {
            throw new NoSuchElementException("Agent 不存在");
        }
        return result;
    }
    /**
     * removeToolFromAgent：移除 Agent 与工具的关联。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param agentId 目标 Agent 标识，用于定位关联的 Agent 定义。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public AgentDefinition removeToolFromAgent(UUID tenantId, UUID agentId, UUID toolId) {
        AtomicReference<AgentDefinition> updated = new AtomicReference<>();
        agents.computeIfPresent(agentId, (id, agent) -> {
            if (!agent.tenantId().equals(tenantId)) {
                throw new NoSuchElementException("Agent 不存在");
            }
            if (!agent.toolIds().contains(toolId)) {
                updated.set(agent);
                return agent;
            }
            AgentDefinition reduced = new AgentDefinition(
                    agent.id(),
                    agent.tenantId(),
                    agent.name(),
                    agent.description(),
                    agent.systemPrompt(),
                    agent.modelProviderId(),
                    agent.modelName(),
                    agent.temperature(),
                    agent.maxIterations(),
                    agent.enabled(),
                    agent.toolIds().stream().filter(idToKeep -> !idToKeep.equals(toolId)).toList(),
                    agent.createdBy(),
                    agent.updatedBy()
            );
            updated.set(reduced);
            return reduced;
        });
        AgentDefinition result = updated.get();
        if (result == null) {
            throw new NoSuchElementException("Agent 不存在");
        }
        return result;
    }
    /**
     * 保存传入的领域对象或配置，并返回当前存储快照。
     *
     * @param tool 当前处理的工具定义。
     */
    public ToolDefinition saveTool(ToolDefinition tool) {
        Objects.requireNonNull(tool, "tool 不能为空");
        TenantToolName name = new TenantToolName(tool.tenantId(), tool.name());
        synchronized (toolLock) {
            if (deletedToolIds.contains(tool.id())) {
                throw new DuplicateKeyException("duplicate key tool_definitions_pkey");
            }
            UUID existingToolId = toolIdsByTenantAndName.putIfAbsent(name, tool.id());
            if (existingToolId != null && !existingToolId.equals(tool.id())) {
                throw new DuplicateKeyException("duplicate key ux_tool_definitions_tenant_name");
            }
            ToolDefinition existing = tools.putIfAbsent(tool.id(), tool);
            if (existing != null) {
                if (!existing.tenantId().equals(tool.tenantId()) || !existing.name().equals(tool.name())) {
                    toolIdsByTenantAndName.remove(name, tool.id());
                    throw new DuplicateKeyException("duplicate key tool_definitions_pkey");
                }
                return existing;
            }
            return tool;
        }
    }

    /**
     * 原位恢复与墓碑身份完全匹配的受管 LOCAL 工具。
     *
     * @param tool 待恢复的受管 LOCAL 工具定义
     * @return 是否成功恢复
     */
    public boolean restoreManagedLocalTool(ToolDefinition tool) {
        Objects.requireNonNull(tool, "tool 不能为空");
        if (tool.type() != ToolType.LOCAL) {
            throw new IllegalArgumentException("只能恢复受管 LOCAL 工具");
        }
        synchronized (toolLock) {
            ToolDefinition deleted = tools.get(tool.id());
            if (deleted == null || !deletedToolIds.contains(tool.id())
                    || !deleted.tenantId().equals(tool.tenantId())
                    || !deleted.name().equals(tool.name())
                    || deleted.type() != ToolType.LOCAL) {
                return false;
            }
            TenantToolName name = new TenantToolName(tool.tenantId(), tool.name());
            UUID existingToolId = toolIdsByTenantAndName.putIfAbsent(name, tool.id());
            if (existingToolId != null && !existingToolId.equals(tool.id())) {
                throw new DuplicateKeyException("duplicate key ux_tool_definitions_tenant_name");
            }
            ToolDefinition restored = new ToolDefinition(
                    deleted.id(),
                    deleted.tenantId(),
                    tool.name(),
                    tool.description(),
                    deleted.type(),
                    tool.inputSchema(),
                    tool.riskLevel(),
                    tool.enabled(),
                    tool.endpoint(),
                    deleted.createdBy(),
                    tool.updatedBy()
            );
            tools.put(tool.id(), restored);
            deletedToolIds.remove(tool.id());
            return true;
        }
    }

    /**
     * 恢复当前命令刚软删除的完整快照，仅供无事务命令失败补偿。
     *
     * @param tool 删除前的完整工具快照
     * @return 是否成功恢复
     */
    public boolean restoreDeletedToolForCompensation(ToolDefinition tool) {
        Objects.requireNonNull(tool, "tool 不能为空");
        synchronized (toolLock) {
            ToolDefinition deleted = tools.get(tool.id());
            if (deleted == null || !deletedToolIds.contains(tool.id())
                    || !deleted.tenantId().equals(tool.tenantId())
                    || !deleted.name().equals(tool.name())
                    || deleted.type() != tool.type()) {
                return false;
            }
            TenantToolName name = new TenantToolName(tool.tenantId(), tool.name());
            UUID existingToolId = toolIdsByTenantAndName.putIfAbsent(name, tool.id());
            if (existingToolId != null && !existingToolId.equals(tool.id())) {
                throw new DuplicateKeyException("duplicate key ux_tool_definitions_tenant_name");
            }
            tools.put(tool.id(), tool);
            deletedToolIds.remove(tool.id());
            return true;
        }
    }
    /**
     * updateTool：更新已存在工具的可编辑字段。
     *
     * @param tool 当前处理的工具定义。
     */
    public ToolDefinition updateTool(ToolDefinition tool) {
        Objects.requireNonNull(tool, "tool 不能为空");
        synchronized (toolLock) {
            ToolDefinition existing = tools.get(tool.id());
            if (existing == null || deletedToolIds.contains(tool.id())
                    || !existing.tenantId().equals(tool.tenantId())) {
                throw new NoSuchElementException("工具不存在");
            }
            TenantToolName originalName = new TenantToolName(existing.tenantId(), existing.name());
            TenantToolName updatedName = new TenantToolName(tool.tenantId(), tool.name());
            if (!originalName.equals(updatedName)) {
                UUID existingToolId = toolIdsByTenantAndName.putIfAbsent(updatedName, tool.id());
                if (existingToolId != null && !existingToolId.equals(tool.id())) {
                    throw new DuplicateKeyException("duplicate key ux_tool_definitions_tenant_name");
                }
                toolIdsByTenantAndName.remove(originalName, tool.id());
            }
            ToolDefinition merged = new ToolDefinition(
                    existing.id(),
                    existing.tenantId(),
                    tool.name(),
                    tool.description(),
                    existing.type(),
                    tool.inputSchema(),
                    tool.riskLevel(),
                    tool.enabled(),
                    tool.endpoint(),
                    existing.createdBy(),
                    tool.updatedBy()
            );
            tools.put(tool.id(), merged);
            return tool;
        }
    }
    /**
     * 按租户和工具标识查询定义，防止返回跨租户数据。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public Optional<ToolDefinition> findTool(UUID tenantId, UUID toolId) {
        ToolDefinition tool = tools.get(toolId);
        if (tool == null || deletedToolIds.contains(toolId) || !tool.tenantId().equals(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(tool);
    }
    /**
     * 删除当前租户边界内的目标记录或关联。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public void deleteTool(UUID tenantId, UUID toolId) {
        synchronized (toolLock) {
            ToolDefinition tool = tools.get(toolId);
            if (tool == null || !tenantId.equals(tool.tenantId())) {
                return;
            }
            deletedToolIds.add(toolId);
            toolIdsByTenantAndName.remove(new TenantToolName(tenantId, tool.name()), toolId);
        }
    }
    /**
     * 列出当前范围内符合条件的记录。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     */
    public List<ToolDefinition> listTools(UUID tenantId) {
        return tools.values().stream()
                .filter(tool -> tool.tenantId().equals(tenantId) && !deletedToolIds.contains(tool.id()))
                .sorted(Comparator.comparing(ToolDefinition::name, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(tool -> tool.id().toString()))
                .toList();
    }

    /**
     * 判断指定工具是否已经产生需要保留的调用历史。
     *
     * @param tenantId 当前租户标识
     * @param toolId 目标工具标识
     * @return 存在调用历史时返回 {@code true}
     */
    public boolean hasToolCallHistory(UUID tenantId, UUID toolId) {
        synchronized (toolCalls) {
            return toolCalls.stream()
                    .anyMatch(toolCall -> tenantId.equals(toolCall.tenantId())
                            && toolId.equals(toolCall.toolId()));
        }
    }
    /**
     * 保存传入的领域对象或配置，并返回当前存储快照。
     *
     * @param grant 待保存或校验的工具授权。
     */
    public ToolGrant saveGrant(ToolGrant grant) {
        synchronized (grants) {
            return grants.stream()
                    .filter(existing -> existing.tenantId().equals(grant.tenantId())
                            && existing.agentId().equals(grant.agentId())
                            && existing.toolId().equals(grant.toolId()))
                    .findFirst()
                    .orElseGet(() -> {
                        grants.add(grant);
                        return grant;
                    });
        }
    }
    /**
     * 列出当前范围内符合条件的记录。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     */
    public List<ToolGrant> listGrants(UUID tenantId) {
        synchronized (grants) {
            return grants.stream()
                    .filter(grant -> grant.tenantId().equals(tenantId))
                    .toList();
        }
    }
    /**
     * 列出当前范围内符合条件的记录。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param agentId 目标 Agent 标识，用于定位关联的 Agent 定义。
     */
    public List<ToolGrant> listGrants(UUID tenantId, UUID agentId) {
        synchronized (grants) {
            return grants.stream()
                    .filter(grant -> grant.tenantId().equals(tenantId) && grant.agentId().equals(agentId))
                    .toList();
        }
    }
    /**
     * 列出当前范围内符合条件的记录。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param agentId 目标 Agent 标识，用于定位关联的 Agent 定义。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public List<ToolGrant> listGrants(UUID tenantId, UUID agentId, UUID toolId) {
        synchronized (grants) {
            return grants.stream()
                    .filter(grant -> grant.tenantId().equals(tenantId)
                            && grant.agentId().equals(agentId)
                            && grant.toolId().equals(toolId))
                    .toList();
        }
    }

    /**
     * deleteGrant：移除指定 Agent 与工具的授权关系。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param agentId 目标 Agent 标识，用于定位关联的 Agent 定义。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public void deleteGrant(UUID tenantId, UUID agentId, UUID toolId) {
        synchronized (grants) {
            grants.removeIf(grant -> grant.tenantId().equals(tenantId)
                    && grant.agentId().equals(agentId)
                    && grant.toolId().equals(toolId));
        }
    }

    /**
     * deleteGrantsByTenantAndToolId：移除指定工具的全部授权关系。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public void deleteGrantsByTenantAndToolId(UUID tenantId, UUID toolId) {
        synchronized (grants) {
            grants.removeIf(grant -> grant.tenantId().equals(tenantId) && grant.toolId().equals(toolId));
        }
    }

    @Override
    /**
     * 线程安全地追加单条审计事件。
     *
     * @param event 待处理的审计事件。
     */
    public void append(AuditEvent event) {
        synchronized (auditEvents) {
            auditEvents.add(event);
        }
    }

    @Override
    /**
     * 线程安全地批量追加审计事件快照。
     *
     * @param events 待追加的审计事件集合
     */
    public void appendAll(List<AuditEvent> events) {
        Objects.requireNonNull(events, "events 不能为空");
        synchronized (auditEvents) {
            auditEvents.addAll(List.copyOf(events));
        }
    }

    @Override
    /**
     * 按租户及方法声明的条件列出匹配记录。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param limit 单页最大返回数量。
     */
    public List<AuditEvent> listByTenant(UUID tenantId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        synchronized (auditEvents) {
            return auditEvents.stream()
                    .filter(event -> event.tenantId().equals(tenantId))
                    .sorted(auditEventOrder())
                    .limit(limit)
                    .toList();
        }
    }

    @Override
    /**
     * 声明当前仓储支持稳定复合游标分页。
     */
    public boolean supportsCursorPagination() {
        return true;
    }

    @Override
    /**
     * 按租户及方法声明的条件列出匹配记录。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param pageRequest 分页查询参数，定义查询范围和游标。
     */
    public List<AuditEvent> listByTenant(UUID tenantId, AuditPageRequest pageRequest) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(pageRequest, "pageRequest 不能为空");
        synchronized (auditEvents) {
            return auditEvents.stream()
                    .filter(event -> tenantId.equals(event.tenantId()))
                    .filter(event -> isBeforeAuditCursor(event, pageRequest))
                    .sorted(auditEventOrder())
                    .limit(pageRequest.limit())
                    .toList();
        }
    }
    /**
     * 列出当前范围内符合条件的记录。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     */
    public List<AuditEvent> listAuditEvents(UUID tenantId) {
        return listAuditEvents(tenantId, 100);
    }
    /**
     * 列出当前范围内符合条件的记录。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param limit 单页最大返回数量。
     */
    public List<AuditEvent> listAuditEvents(UUID tenantId, int limit) {
        return listByTenant(tenantId, limit);
    }

    @Override
    /**
     * 保存传入的领域对象或配置，并返回当前存储快照。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param run 当前处理的运行记录。
     */
    public RunRecord save(UUID tenantId, RunRecord run) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(run, "run 不能为空");
        if (!tenantId.equals(run.tenantId())) {
            throw new IllegalArgumentException("tenantId 与 run.tenantId 不匹配");
        }
        if (runs.putIfAbsent(run.id(), run) != null) {
            throw new IllegalStateException("Run 已存在");
        }
        return run;
    }

    @Override
    /**
     * 完成当前状态转换并返回最终结果。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param runId 目标运行记录标识，用于定位关联的执行数据。
     * @param status 当前处理状态，用于驱动状态分支或记录结果。
     * @param output 本次处理产生或待处理的输出内容。
     * @param errorMessage 已控制敏感信息的错误说明。
     * @param finishedAt 运行进入终态的时间
     */
    public RunRecord complete(
            UUID tenantId,
            UUID runId,
            RunStatus status,
            String output,
            String errorMessage,
            java.time.Instant finishedAt
    ) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        RunRecord existing = findByTenantAndId(tenantId, runId)
                .orElseThrow(() -> new NoSuchElementException("Run 不存在"));
        RunRecord completed = existing.complete(status, output, errorMessage, finishedAt);
        if (!runs.replace(runId, existing, completed)) {
            throw new NoSuchElementException("Run 不存在");
        }
        return completed;
    }

    @Override
    /**
     * 按租户及方法声明的标识查询匹配记录。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param agentId 目标 Agent 标识，用于定位关联的 Agent 定义。
     * @param runId 目标运行记录标识，用于定位关联的执行数据。
     */
    public Optional<RunRecord> findByTenantAndAgentAndId(UUID tenantId, UUID agentId, UUID runId) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        RunRecord run = runs.get(runId);
        if (run == null || !tenantId.equals(run.tenantId()) || !agentId.equals(run.agentId())) {
            return Optional.empty();
        }
        return Optional.of(run);
    }

    @Override
    /**
     * 按租户及方法声明的条件列出匹配记录。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param agentId 目标 Agent 标识，用于定位关联的 Agent 定义。
     * @param pageRequest 分页查询参数，定义查询范围和游标。
     */
    public List<RunRecord> listByTenantAndAgent(UUID tenantId, UUID agentId, RunPageRequest pageRequest) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        Objects.requireNonNull(pageRequest, "pageRequest 不能为空");
        return runs.values().stream()
                .filter(run -> tenantId.equals(run.tenantId()) && agentId.equals(run.agentId()))
                .filter(run -> RunRepository.isStrictlyBeforeCursor(run, pageRequest))
                .sorted(RunRepository.keysetOrder())
                .limit(pageRequest.limit())
                .toList();
    }

    @Override
    /**
     * 保存传入的领域对象或配置，并返回当前存储快照。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolCallBatch 同一运行下待原子保存的工具调用批次
     */
    public void saveAll(UUID tenantId, RunToolCallBatch toolCallBatch) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(toolCallBatch, "toolCalls 不能为空");
        toolCallBatch.requireTenant(tenantId);
        synchronized (toolCalls) {
            HashSet<UUID> batchIds = new HashSet<>();
            for (RunToolCall toolCall : toolCallBatch.toolCalls()) {
                RunRecord run = runs.get(toolCall.runId());
                if (run == null || !tenantId.equals(run.tenantId())) {
                    throw new IllegalArgumentException("toolCall 的 run 不存在或 tenant 不匹配");
                }
                ToolDefinition tool = tools.get(toolCall.toolId());
                if (tool == null || !tenantId.equals(tool.tenantId())) {
                    throw new IllegalArgumentException("toolCall 的 tool 不存在或 tenant 不匹配");
                }
                if (!batchIds.add(toolCall.id())
                        || toolCalls.stream().anyMatch(existing -> existing.id().equals(toolCall.id()))) {
                    throw new IllegalStateException("ToolCall 已存在");
                }
            }
            toolCalls.addAll(toolCallBatch.toolCalls());
        }
    }

    @Override
    /**
     * 按租户及方法声明的条件列出匹配记录。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param runId 目标运行记录标识，用于定位关联的执行数据。
     */
    public List<RunToolCall> listByTenantAndRun(UUID tenantId, UUID runId) {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        synchronized (toolCalls) {
            return toolCalls.stream()
                    .filter(toolCall -> tenantId.equals(toolCall.tenantId()) && runId.equals(toolCall.runId()))
                    .sorted(Comparator.comparing(RunToolCall::createdAt).thenComparing(RunToolCall::id))
                    .toList();
        }
    }

    /**
     * 按租户及方法声明的标识查询匹配记录。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param runId 目标运行记录标识，用于定位关联的执行数据。
     */
    private Optional<RunRecord> findByTenantAndId(UUID tenantId, UUID runId) {
        RunRecord run = runs.get(runId);
        if (run == null || !tenantId.equals(run.tenantId())) {
            return Optional.empty();
        }
        return Optional.of(run);
    }

    /**
     * 返回审计事件复合游标使用的稳定排序器。
     */
    private static Comparator<AuditEvent> auditEventOrder() {
        return Comparator.comparing(AuditEvent::createdAt).reversed()
                .thenComparing(AuditEvent::id, (left, right) -> right.toString().compareTo(left.toString()));
    }

    /**
     * 判断审计事件是否严格位于复合游标之后一页。
     *
     * @param event 待处理的审计事件。
     * @param pageRequest 分页查询参数，定义查询范围和游标。
     */
    private static boolean isBeforeAuditCursor(AuditEvent event, AuditPageRequest pageRequest) {
        if (pageRequest.beforeCreatedAt() == null) {
            return true;
        }
        int createdAtComparison = event.createdAt().compareTo(pageRequest.beforeCreatedAt());
        return createdAtComparison < 0
                || (createdAtComparison == 0
                && event.id().toString().compareTo(pageRequest.beforeId().toString()) < 0);
    }
}
