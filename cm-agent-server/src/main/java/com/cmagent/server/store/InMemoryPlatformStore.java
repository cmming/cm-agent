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
    private final ConcurrentHashMap<UUID, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TenantToolName, UUID> toolIdsByTenantAndName = new ConcurrentHashMap<>();
    private final Object toolLock = new Object();
    private final List<ToolGrant> grants = Collections.synchronizedList(new ArrayList<>());
    private final List<AuditEvent> auditEvents = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentHashMap<UUID, RunRecord> runs = new ConcurrentHashMap<>();
    private final List<RunToolCall> toolCalls = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentHashMap<String, HttpToolConfig> httpToolConfigs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, McpToolPublication> mcpToolPublications = new ConcurrentHashMap<>();
    /**
     * saveHttpToolConfig：保存当前对象及其关联配置。
     *
     * @param config 待处理的工具或运行时配置。
     */
    public HttpToolConfig saveHttpToolConfig(HttpToolConfig config) {
        httpToolConfigs.put(toolKey(config.tenantId(), config.toolId()), config);
        return config;
    }
    /**
     * findHttpToolConfig：查询并返回当前上下文中的匹配结果。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public Optional<HttpToolConfig> findHttpToolConfig(UUID tenantId, UUID toolId) {
        return Optional.ofNullable(httpToolConfigs.get(toolKey(tenantId, toolId)))
                .filter(config -> tenantId.equals(config.tenantId()) && toolId.equals(config.toolId()));
    }
    /**
     * deleteHttpToolConfig：删除或撤销当前目标的关联状态。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public void deleteHttpToolConfig(UUID tenantId, UUID toolId) {
        httpToolConfigs.remove(toolKey(tenantId, toolId));
    }
    /**
     * saveMcpToolPublication：保存当前对象及其关联配置。
     *
     * @param publication 待保存或校验的 MCP 发布记录。
     */
    public McpToolPublication saveMcpToolPublication(McpToolPublication publication) {
        mcpToolPublications.put(toolKey(publication.tenantId(), publication.toolId()), publication);
        return publication;
    }
    /**
     * findMcpToolPublication：查询并返回当前上下文中的匹配结果。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public Optional<McpToolPublication> findMcpToolPublication(UUID tenantId, UUID toolId) {
        return Optional.ofNullable(mcpToolPublications.get(toolKey(tenantId, toolId)))
                .filter(publication -> tenantId.equals(publication.tenantId()) && toolId.equals(publication.toolId()));
    }
    /**
     * deleteMcpToolPublication：删除或撤销当前目标的关联状态。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public void deleteMcpToolPublication(UUID tenantId, UUID toolId) {
        mcpToolPublications.remove(toolKey(tenantId, toolId));
    }
    /**
     * listEnabledMcpToolPublications：查询并返回符合条件的集合。
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
     * toolKey：转换内部数据为目标表示。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    private static String toolKey(UUID tenantId, UUID toolId) {
        return tenantId + ":" + toolId;
    }

    /**
     * TenantToolName：不可变数据载体，用于在本模块内传递结构化信息。
     */
    private record TenantToolName(UUID tenantId, String name) {
    }
    /**
     * saveModelConfig：保存当前对象及其关联配置。
     *
     * @param modelConfig modelConfig 对应的配置数据，用于驱动本次处理。
     */
    public ModelConfig saveModelConfig(ModelConfig modelConfig) {
        modelConfigs.put(modelConfig.id(), modelConfig);
        return modelConfig;
    }
    /**
     * findModelConfig：查询并返回当前上下文中的匹配结果。
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
     * saveAgent：保存当前对象及其关联配置。
     *
     * @param agent 当前处理的 Agent 定义。
     */
    public AgentDefinition saveAgent(AgentDefinition agent) {
        agents.put(agent.id(), agent);
        return agent;
    }
    /**
     * findAgent：查询并返回当前上下文中的匹配结果。
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
     * listAgents：查询并返回符合条件的集合。
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
     * addToolToAgent：处理该类内部的业务逻辑或辅助计算。
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
     * saveTool：保存当前对象及其关联配置。
     *
     * @param tool 当前处理的工具定义。
     */
    public ToolDefinition saveTool(ToolDefinition tool) {
        Objects.requireNonNull(tool, "tool 不能为空");
        TenantToolName name = new TenantToolName(tool.tenantId(), tool.name());
        synchronized (toolLock) {
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
     * updateTool：更新已存在工具的可编辑字段。
     *
     * @param tool 当前处理的工具定义。
     */
    public ToolDefinition updateTool(ToolDefinition tool) {
        Objects.requireNonNull(tool, "tool 不能为空");
        synchronized (toolLock) {
            ToolDefinition existing = tools.get(tool.id());
            if (existing == null || !existing.tenantId().equals(tool.tenantId())) {
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
     * findTool：查询并返回当前上下文中的匹配结果。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolId 目标工具标识，用于定位关联的工具定义。
     */
    public Optional<ToolDefinition> findTool(UUID tenantId, UUID toolId) {
        ToolDefinition tool = tools.get(toolId);
        if (tool == null || !tool.tenantId().equals(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(tool);
    }
    /**
     * deleteTool：删除或撤销当前目标的关联状态。
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
            tools.remove(toolId, tool);
            toolIdsByTenantAndName.remove(new TenantToolName(tenantId, tool.name()), toolId);
        }
    }
    /**
     * listTools：查询并返回符合条件的集合。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     */
    public List<ToolDefinition> listTools(UUID tenantId) {
        return tools.values().stream()
                .filter(tool -> tool.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(ToolDefinition::name, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(tool -> tool.id().toString()))
                .toList();
    }

    /**
     * 判断指定工具是否已经产生需要保留的调用历史。
     */
    public boolean hasToolCallHistory(UUID tenantId, UUID toolId) {
        synchronized (toolCalls) {
            return toolCalls.stream()
                    .anyMatch(toolCall -> tenantId.equals(toolCall.tenantId())
                            && toolId.equals(toolCall.toolId()));
        }
    }
    /**
     * saveGrant：保存当前对象及其关联配置。
     *
     * @param grant 参与 saveGrant 处理的 grant 输入值。
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
     * listGrants：查询并返回符合条件的集合。
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
     * listGrants：查询并返回符合条件的集合。
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
     * listGrants：查询并返回符合条件的集合。
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
     * append：追加处理结果或审计记录。
     *
     * @param event 参与 append 处理的 event 输入值。
     */
    public void append(AuditEvent event) {
        synchronized (auditEvents) {
            auditEvents.add(event);
        }
    }

    @Override
    /**
     * appendAll：追加处理结果或审计记录。
     *
     * @param events 参与 appendAll 处理的 events 集合。
     */
    public void appendAll(List<AuditEvent> events) {
        Objects.requireNonNull(events, "events 不能为空");
        synchronized (auditEvents) {
            auditEvents.addAll(List.copyOf(events));
        }
    }

    @Override
    /**
     * listByTenant：查询并返回符合条件的集合。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param limit 参与 listByTenant 处理的 limit 输入值。
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
     * supportsCursorPagination：处理该类内部的业务逻辑或辅助计算。
     */
    public boolean supportsCursorPagination() {
        return true;
    }

    @Override
    /**
     * listByTenant：查询并返回符合条件的集合。
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
     * listAuditEvents：查询并返回符合条件的集合。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     */
    public List<AuditEvent> listAuditEvents(UUID tenantId) {
        return listAuditEvents(tenantId, 100);
    }
    /**
     * listAuditEvents：查询并返回符合条件的集合。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param limit 参与 listAuditEvents 处理的 limit 输入值。
     */
    public List<AuditEvent> listAuditEvents(UUID tenantId, int limit) {
        return listByTenant(tenantId, limit);
    }

    @Override
    /**
     * save：保存当前对象及其关联配置。
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
     * complete：处理该类内部的业务逻辑或辅助计算。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param runId 目标运行记录标识，用于定位关联的执行数据。
     * @param status 当前处理状态，用于驱动状态分支或记录结果。
     * @param output 本次处理产生或待处理的输出内容。
     * @param errorMessage 参与 complete 处理的 errorMessage 输入值。
     * @param finishedAt 参与 complete 处理的 finishedAt 输入值。
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
     * findByTenantAndAgentAndId：查询并返回当前上下文中的匹配结果。
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
     * listByTenantAndAgent：查询并返回符合条件的集合。
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
     * saveAll：保存当前对象及其关联配置。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param toolCallBatch 参与 saveAll 处理的 toolCallBatch 输入值。
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
     * listByTenantAndRun：查询并返回符合条件的集合。
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
     * findByTenantAndId：查询并返回当前上下文中的匹配结果。
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
     * auditEventOrder：处理该类内部的业务逻辑或辅助计算。
     */
    private static Comparator<AuditEvent> auditEventOrder() {
        return Comparator.comparing(AuditEvent::createdAt).reversed()
                .thenComparing(AuditEvent::id, (left, right) -> right.toString().compareTo(left.toString()));
    }

    /**
     * isBeforeAuditCursor：判断当前条件是否成立。
     *
     * @param event 参与 isBeforeAuditCursor 处理的 event 输入值。
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
