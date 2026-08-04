package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.McpToolPublication;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.http.HttpToolConfigValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
/** 管理类写操作的统一编排层，负责校验、持久化和必要的审计动作。 */
public class ManagementCommandService {
    private static final UUID MODEL_PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final int TOOL_LOCK_STRIPE_COUNT = 256;
    private static final ReentrantLock[] TOOL_LOCKS = createToolLocks();
    private static final ReentrantLock[] AGENT_LOCKS = createToolLocks();

    private final AgentDefinitionRepository agentRepository;
    private final ToolDefinitionRepository toolRepository;
    private final HttpToolConfigRepository httpToolConfigRepository;
    private final McpToolPublicationRepository mcpToolPublicationRepository;
    private final ToolGrantRepository grantRepository;
    private final AuditAppender auditAppender;
    private final HttpToolConfigValidator httpToolConfigValidator;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    /**
     * 创建 {@code ManagementCommandService} 实例并保存其运行所需依赖。
     *
     * @param agentRepository 负责访问相关领域数据的仓储。
     * @param toolRepository 工具定义仓储。
     * @param httpToolConfigRepository 负责访问相关领域数据的仓储。
     * @param mcpToolPublicationRepository 负责访问相关领域数据的仓储。
     * @param grantRepository 负责访问相关领域数据的仓储。
     * @param auditAppender 负责追加安全审计事件的组件。
     * @param httpToolConfigValidator 动态 HTTP 工具配置校验器
     * @param transactionTemplate 保证多步写入原子性的事务模板。
     */
    public ManagementCommandService(
            AgentDefinitionRepository agentRepository,
            ToolDefinitionRepository toolRepository,
            HttpToolConfigRepository httpToolConfigRepository,
            McpToolPublicationRepository mcpToolPublicationRepository,
            ToolGrantRepository grantRepository,
            AuditAppender auditAppender,
            HttpToolConfigValidator httpToolConfigValidator,
            @Nullable TransactionTemplate transactionTemplate
    ) {
        this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository 不能为空");
        this.toolRepository = Objects.requireNonNull(toolRepository, "toolRepository 不能为空");
        this.httpToolConfigRepository = Objects.requireNonNull(httpToolConfigRepository, "httpToolConfigRepository 不能为空");
        this.mcpToolPublicationRepository = Objects.requireNonNull(mcpToolPublicationRepository, "mcpToolPublicationRepository 不能为空");
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository 不能为空");
        this.auditAppender = Objects.requireNonNull(auditAppender, "auditAppender 不能为空");
        this.httpToolConfigValidator = Objects.requireNonNull(httpToolConfigValidator,
                "httpToolConfigValidator 不能为空");
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 创建 Agent，并写入创建审计。
     *
     * @param principal    当前认证主体
     * @param name         Agent 名称
     * @param systemPrompt 系统提示词
     * @param modelName    使用的模型名称
     * @return 已保存的 Agent 定义
     * @throws RuntimeException 持久化或审计失败时抛出
     */
    public AgentDefinition createAgent(PrincipalRef principal, String name, String systemPrompt, String modelName) {
        AgentDefinition agent = new AgentDefinition(
                UUID.randomUUID(), principal.tenantId(), name, "", systemPrompt, MODEL_PROVIDER_ID,
                modelName, 0.2d, 6, true, List.of(), principal.principalId(), principal.principalId()
        );
        if (transactionTemplate == null) {
            appendAgentAudit(principal, agent);
            return agentRepository.save(agent);
        }
        return requireResult(transactionTemplate.execute(status -> {
            AgentDefinition saved = agentRepository.save(agent);
            appendAgentAudit(principal, saved);
            return saved;
        }));
    }

    /**
     * 创建不带额外 HTTP 配置的工具。
     *
     * @param principal   当前认证主体
     * @param name        工具名称
     * @param description 工具描述
     * @param type        工具类型
     * @param riskLevel   工具风险等级
     * @return 已保存的工具定义
     * @throws ResponseStatusException 工具名称冲突或参数不合法时抛出
     */
    public ToolDefinition createTool(
            PrincipalRef principal, String name, String description, ToolType type, ToolRiskLevel riskLevel
    ) {
        return createTool(principal, name, description, type, riskLevel, null, false);
    }

    /**
     * 创建工具，并按需保存 HTTP 配置和 MCP 发布记录。
     *
     * @param principal          当前认证主体
     * @param name               工具名称
     * @param description        工具描述
     * @param type               工具类型
     * @param riskLevel          工具风险等级
     * @param httpToolCreateSpec HTTP 工具配置；非 HTTP 工具必须为空
     * @param mcpPublished       是否立即发布到 MCP
     * @return 已保存的工具定义
     * @throws ResponseStatusException 配置不合法或工具名称冲突时抛出
     * @throws DuplicateKeyException   持久化层发生未识别的唯一键冲突时抛出
     */
    public ToolDefinition createTool(
            PrincipalRef principal,
            String name,
            String description,
            ToolType type,
            ToolRiskLevel riskLevel,
            @Nullable HttpToolCreateSpec httpToolCreateSpec,
            boolean mcpPublished
    ) {
        validateToolCreateRequest(type, httpToolCreateSpec, mcpPublished);
        ensureToolNameAvailable(principal.tenantId(), name);
        PreparedToolCreate prepared = prepareToolCreate(principal, name, description, type, riskLevel, httpToolCreateSpec, mcpPublished);
        try {
            if (transactionTemplate == null) {
                return saveToolWithCompensation(principal, prepared);
            }
            return requireResult(transactionTemplate.execute(status -> {
                ToolDefinition saved = saveToolWithHttpConfiguration(prepared);
                appendToolAudit(principal, saved, prepared.mcpToolPublication() != null);
                return saved;
            }));
        } catch (DuplicateKeyException exception) {
            if (isToolNameConflict(exception)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前租户下工具名称已存在", exception);
            }
            throw exception;
        }
    }

    /**
     * 校验创建请求并构造工具及其附属配置。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param name 目标对象的名称。
     * @param description 目标对象说明。
     * @param type 待加入或校验的 Schema 值类型。
     * @param riskLevel 目标工具风险等级。
     * @param httpToolCreateSpec 可选的 HTTP 工具创建规格。
     * @param mcpPublished 是否同时发布为 MCP 工具。
     */
    private PreparedToolCreate prepareToolCreate(
            PrincipalRef principal,
            String name,
            String description,
            ToolType type,
            ToolRiskLevel riskLevel,
            @Nullable HttpToolCreateSpec httpToolCreateSpec,
            boolean mcpPublished
    ) {
        String inputSchema = httpToolCreateSpec == null ? "{\"type\":\"object\"}" : httpToolCreateSpec.inputSchema();
        String endpoint = httpToolCreateSpec == null ? "" : httpToolCreateSpec.urlTemplate();
        ToolDefinition tool = new ToolDefinition(
                UUID.randomUUID(), principal.tenantId(), name, description, type, inputSchema,
                riskLevel, true, endpoint, principal.principalId(), principal.principalId()
        );
        if (httpToolCreateSpec == null) {
            return new PreparedToolCreate(tool, null, null);
        }
        HttpToolConfig configuration = new HttpToolConfig(
                principal.tenantId(), tool.id(), httpToolCreateSpec.method(), httpToolCreateSpec.urlTemplate(),
                httpToolCreateSpec.inputSchema(), httpToolCreateSpec.parameterMappings(), httpToolCreateSpec.secretHeaders(),
                httpToolCreateSpec.timeout()
        );
        httpToolConfigValidator.validate(configuration);
        if (mcpPublished) {
            McpToolPublicationRules.validateHttp(tool, configuration);
        }
        McpToolPublication publication = mcpPublished
                ? new McpToolPublication(principal.tenantId(), tool.id(), true, principal.principalId())
                : null;
        return new PreparedToolCreate(tool, configuration, publication);
    }

    /**
     * 将工具授权给同一租户下的 Agent。
     *
     * @param principal 当前认证主体
     * @param toolId    工具标识
     * @param agentId   Agent 标识
     * @return 已保存的授权记录
     * @throws ResponseStatusException 工具或 Agent 不存在时抛出
     * @throws RuntimeException        授权、关联或审计失败时抛出
     */
    public ToolGrant grantTool(PrincipalRef principal, UUID toolId, UUID agentId) {
        return withAgentLock(
                principal.tenantId(),
                agentId,
                () -> withToolLock(
                        principal.tenantId(),
                        toolId,
                        () -> grantToolWithLock(principal, toolId, agentId)
                )
        );
    }

    /**
     * 在 Agent 级锁内完成工具授权，防止并发覆盖。
     *
     * @param principal 当前认证主体
     * @param toolId 目标工具标识
     * @param agentId 目标 Agent 标识
     */
    private ToolGrant grantToolWithLock(PrincipalRef principal, UUID toolId, UUID agentId) {
        if (transactionTemplate == null) {
            ToolDefinition tool = findTool(principal.tenantId(), toolId);
            AgentDefinition agent = findAgent(principal.tenantId(), agentId);
            ToolGrant grant = new ToolGrant(principal.tenantId(), tool.id(), agent.id(), null, true);
            appendGrantAudit(principal, tool, agent);
            ToolGrant saved = grantRepository.save(grant);
            agentRepository.addToolToAgent(principal.tenantId(), agent.id(), tool.id());
            return saved;
        }
        return requireResult(transactionTemplate.execute(status -> {
            ToolDefinition tool = lockTool(principal.tenantId(), toolId);
            AgentDefinition agent = findAgent(principal.tenantId(), agentId);
            ToolGrant grant = new ToolGrant(principal.tenantId(), tool.id(), agent.id(), null, true);
            ToolGrant saved = grantRepository.save(grant);
            agentRepository.addToolToAgent(principal.tenantId(), agent.id(), tool.id());
            appendGrantAudit(principal, tool, agent);
            return saved;
        }));
    }

    /**
     * 更新同一租户中的工具定义及其附属配置。
     *
     * @param principal 当前认证主体
     * @param toolId    工具标识
     * @param spec      工具可编辑配置
     * @return 本次命令提交的完整工具响应快照
     * @throws ResponseStatusException 工具不存在、不可变字段被修改或名称冲突时抛出
     */
    public ToolUpdateResult updateTool(PrincipalRef principal, UUID toolId, ToolUpdateSpec spec) {
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(toolId, "toolId 不能为空");
        Objects.requireNonNull(spec, "spec 不能为空");
        return withToolLock(principal.tenantId(), toolId, () -> {
            try {
                if (transactionTemplate == null) {
                    return updateToolWithCompensation(principal, toolId, spec);
                }
                return requireResult(transactionTemplate.execute(
                        status -> updateToolAndAudit(principal, toolId, spec)
                ));
            } catch (DuplicateKeyException exception) {
                if (isToolNameConflict(exception)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "当前租户下工具名称已存在", exception);
                }
                throw exception;
            }
        });
    }

    /**
     * 删除未被 Agent 引用的工具及其全部附属数据。
     *
     * @param principal 当前认证主体
     * @param toolId    工具标识
     * @throws ResponseStatusException 工具不存在、仍被 Agent 引用或已有调用历史时抛出
     */
    public void deleteTool(PrincipalRef principal, UUID toolId) {
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(toolId, "toolId 不能为空");
        withToolLock(principal.tenantId(), toolId, () -> {
            if (transactionTemplate == null) {
                deleteToolWithCompensation(principal, toolId);
                return;
            }
            transactionTemplate.executeWithoutResult(status -> deleteToolAndAudit(principal, toolId));
        });
    }

    /**
     * 解除 Agent 与工具的关联并删除对应授权。
     *
     * @param principal 当前认证主体
     * @param toolId    工具标识
     * @param agentId   Agent 标识
     * @return 更新后的 Agent 定义
     * @throws ResponseStatusException 工具或 Agent 不存在时抛出
     */
    public AgentDefinition revokeTool(PrincipalRef principal, UUID toolId, UUID agentId) {
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(toolId, "toolId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        return withAgentLock(
                principal.tenantId(),
                agentId,
                () -> withToolLock(principal.tenantId(), toolId, () -> {
                    if (transactionTemplate == null) {
                        return revokeToolWithCompensation(principal, toolId, agentId);
                    }
                    return requireResult(transactionTemplate.execute(
                            status -> revokeToolAndAudit(principal, toolId, agentId)
                    ));
                })
        );
    }

    /**
     * 在工具级锁内更新工具并记录审计。
     *
     * @param principal 当前认证主体
     * @param toolId 目标工具标识
     * @param spec 已校验的工具创建或更新规格。
     */
    private ToolUpdateResult updateToolAndAudit(PrincipalRef principal, UUID toolId, ToolUpdateSpec spec) {
        PreparedToolUpdate prepared = prepareToolUpdateCommand(principal, toolId, spec, true);
        return applyToolUpdateAndAudit(principal, prepared);
    }

    /**
     * 更新工具及附属状态，并在失败时恢复原快照。
     *
     * @param principal 当前认证主体
     * @param toolId 目标工具标识
     * @param spec 已校验的工具创建或更新规格。
     */
    private ToolUpdateResult updateToolWithCompensation(PrincipalRef principal, UUID toolId, ToolUpdateSpec spec) {
        PreparedToolUpdate prepared = prepareToolUpdateCommand(principal, toolId, spec, false);
        UUID tenantId = principal.tenantId();
        ToolStateSnapshot snapshot = new ToolStateSnapshot(
                prepared.originalTool(),
                httpToolConfigRepository.findByTenantAndToolId(tenantId, toolId),
                mcpToolPublicationRepository.findByTenantAndToolId(tenantId, toolId),
                List.of()
        );
        try {
            return applyToolUpdateAndAudit(principal, prepared);
        } catch (RuntimeException exception) {
            restoreToolState(snapshot, exception);
            throw exception;
        }
    }

    /**
     * 读取并校验工具更新所需的当前状态。
     *
     * @param principal 当前认证主体
     * @param toolId 目标工具标识
     * @param spec 已校验的工具创建或更新规格。
     * @param lockForUpdate 是否使用数据库行锁读取工具。
     */
    private PreparedToolUpdate prepareToolUpdateCommand(
            PrincipalRef principal,
            UUID toolId,
            ToolUpdateSpec spec,
            boolean lockForUpdate
    ) {
        ToolDefinition existing = (lockForUpdate
                ? toolRepository.findByTenantAndIdForUpdate(principal.tenantId(), toolId)
                : toolRepository.findByTenantAndId(principal.tenantId(), toolId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
        Optional<McpToolPublication> existingPublication =
                mcpToolPublicationRepository.findByTenantAndToolId(principal.tenantId(), toolId);
        validateToolUpdateRequest(
                existing,
                spec,
                existingPublication.filter(McpToolPublication::enabled).isPresent()
        );
        ensureToolNameAvailable(principal.tenantId(), toolId, spec.name());
        return prepareToolUpdate(principal, existing, spec);
    }

    /**
     * 应用已准备的工具更新并追加审计。
     *
     * @param principal 当前认证主体
     * @param prepared 已完成前置校验的命令上下文。
     */
    private ToolUpdateResult applyToolUpdateAndAudit(PrincipalRef principal, PreparedToolUpdate prepared) {
        UUID tenantId = principal.tenantId();
        ToolDefinition updated;
        try {
            updated = toolRepository.update(prepared.tool());
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在", exception);
        }
        if (prepared.httpToolConfig() != null) {
            httpToolConfigRepository.save(prepared.httpToolConfig());
        }
        if (prepared.publicationMutation() == PublicationMutation.UPSERT) {
            mcpToolPublicationRepository.save(prepared.mcpToolPublication());
        } else if (prepared.publicationMutation() == PublicationMutation.DELETE) {
            mcpToolPublicationRepository.delete(tenantId, prepared.tool().id());
        }
        appendToolUpdateAudit(principal, updated);
        return new ToolUpdateResult(updated, prepared.httpToolConfig(), prepared.mcpPublished());
    }

    /**
     * 校验更新字段并构造新的工具定义及 HTTP 配置。
     *
     * @param principal 当前认证主体
     * @param existing 变更前的现有领域对象。
     * @param spec 已校验的工具创建或更新规格。
     */
    private PreparedToolUpdate prepareToolUpdate(
            PrincipalRef principal,
            ToolDefinition existing,
            ToolUpdateSpec spec
    ) {
        HttpToolCreateSpec httpSpec = spec.httpToolCreateSpec();
        String inputSchema = httpSpec == null ? existing.inputSchema() : httpSpec.inputSchema();
        String endpoint = httpSpec == null ? existing.endpoint() : httpSpec.urlTemplate();
        ToolDefinition updated = new ToolDefinition(
                existing.id(),
                existing.tenantId(),
                spec.name(),
                spec.description(),
                existing.type(),
                inputSchema,
                spec.riskLevel(),
                spec.enabled(),
                endpoint,
                existing.createdBy(),
                principal.principalId()
        );
        if (httpSpec == null) {
            PublicationMutation publicationMutation = existing.type() == ToolType.LOCAL && !spec.mcpPublished()
                    ? PublicationMutation.DELETE
                    : PublicationMutation.KEEP;
            return new PreparedToolUpdate(existing, updated, null, null, publicationMutation, spec.mcpPublished());
        }

        HttpToolConfig configuration = new HttpToolConfig(
                existing.tenantId(),
                existing.id(),
                httpSpec.method(),
                httpSpec.urlTemplate(),
                httpSpec.inputSchema(),
                httpSpec.parameterMappings(),
                httpSpec.secretHeaders(),
                httpSpec.timeout()
        );
        httpToolConfigValidator.validate(configuration);
        if (spec.mcpPublished()) {
            McpToolPublicationRules.validateHttp(updated, configuration);
        }
        McpToolPublication publication = spec.mcpPublished()
                ? new McpToolPublication(existing.tenantId(), existing.id(), true, principal.principalId())
                : null;
        return new PreparedToolUpdate(
                existing,
                updated,
                configuration,
                publication,
                publication == null ? PublicationMutation.DELETE : PublicationMutation.UPSERT,
                spec.mcpPublished()
        );
    }

    /**
     * 在工具级锁内删除工具并记录审计。
     *
     * @param principal 当前认证主体
     * @param toolId 目标工具标识
     */
    private void deleteToolAndAudit(PrincipalRef principal, UUID toolId) {
        UUID tenantId = principal.tenantId();
        ToolDefinition tool = lockTool(tenantId, toolId);
        validateToolNotReferenced(tenantId, toolId);
        deleteToolStateAndAudit(principal, tool);
    }

    /**
     * 删除工具及附属状态，并在失败时恢复原快照。
     *
     * @param principal 当前认证主体
     * @param toolId 目标工具标识
     */
    private void deleteToolWithCompensation(PrincipalRef principal, UUID toolId) {
        UUID tenantId = principal.tenantId();
        ToolDefinition tool = toolRepository.findByTenantAndId(tenantId, toolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
        validateToolNotReferenced(tenantId, toolId);
        // 无事务仓储先保存工具及其附属数据快照，后续任一步失败时据此恢复可见状态。
        ToolStateSnapshot snapshot = new ToolStateSnapshot(
                tool,
                httpToolConfigRepository.findByTenantAndToolId(tenantId, toolId),
                mcpToolPublicationRepository.findByTenantAndToolId(tenantId, toolId),
                grantRepository.listByTenant(tenantId).stream()
                        .filter(grant -> grant.toolId().equals(toolId))
                        .toList()
        );
        try {
            deleteToolStateAndAudit(principal, tool);
        } catch (RuntimeException exception) {
            // 先恢复工具主体与附属配置，再恢复授权关系；恢复异常作为 suppressed 保留原始失败。
            restoreToolState(snapshot, exception);
            restoreToolGrants(snapshot.tool(), snapshot.grants(), exception);
            throw exception;
        }
    }

    /**
     * 确认工具未被任何 Agent 引用且没有调用历史。
     *
     * @param tenantId 当前租户标识
     * @param toolId 目标工具标识
     */
    private void validateToolNotReferenced(UUID tenantId, UUID toolId) {
        boolean referenced = agentRepository.listByTenant(tenantId).stream()
                .anyMatch(agent -> agent.toolIds().contains(toolId));
        if (referenced) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "工具仍被 Agent 关联，请先解除关联后再删除"
            );
        }
        if (toolRepository.hasToolCallHistory(tenantId, toolId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "工具已有调用历史，为保留运行记录不能删除"
            );
        }
    }

    /**
     * 删除工具的授权、配置、发布状态和可见定义并追加审计。
     *
     * @param principal 当前认证主体
     * @param tool 当前处理的工具定义
     */
    private void deleteToolStateAndAudit(PrincipalRef principal, ToolDefinition tool) {
        UUID tenantId = principal.tenantId();
        httpToolConfigRepository.delete(tenantId, tool.id());
        mcpToolPublicationRepository.delete(tenantId, tool.id());
        grantRepository.deleteByTenantAndToolId(tenantId, tool.id());
        toolRepository.delete(tenantId, tool.id());
        auditAppender.append(
                tenantId,
                principal.principalId(),
                "TOOL_DELETE",
                "TOOL",
                tool.id().toString(),
                "SUCCEEDED",
                "工具删除成功"
        );
    }

    /**
     * 在 Agent 级锁内撤销工具关联和授权并记录审计。
     *
     * @param principal 当前认证主体
     * @param toolId 目标工具标识
     * @param agentId 目标 Agent 标识
     */
    private AgentDefinition revokeToolAndAudit(PrincipalRef principal, UUID toolId, UUID agentId) {
        UUID tenantId = principal.tenantId();
        ToolDefinition tool = toolRepository.findByTenantAndId(tenantId, toolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
        AgentDefinition agent = agentRepository.findByTenantAndId(tenantId, agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
        return revokeToolStateAndAudit(principal, tool, agent);
    }

    /**
     * 撤销工具关联和授权，并在失败时恢复原状态。
     *
     * @param principal 当前认证主体
     * @param toolId 目标工具标识
     * @param agentId 目标 Agent 标识
     */
    private AgentDefinition revokeToolWithCompensation(PrincipalRef principal, UUID toolId, UUID agentId) {
        UUID tenantId = principal.tenantId();
        ToolDefinition tool = toolRepository.findByTenantAndId(tenantId, toolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
        AgentDefinition agent = agentRepository.findByTenantAndId(tenantId, agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
        // 撤销会同时修改 Agent 关联和授权表，因此在无事务模式下提前保存授权快照。
        List<ToolGrant> grants = grantRepository.listByTenantAgentAndTool(tenantId, agentId, toolId);
        try {
            return revokeToolStateAndAudit(principal, tool, agent);
        } catch (RuntimeException exception) {
            // 关联或审计失败时恢复 Agent 工具列表及原授权，保持命令对调用方呈现原子语义。
            restoreAgentToolState(agent, tool.id(), grants, exception);
            throw exception;
        }
    }

    /**
     * 删除 Agent 关联与授权并追加撤销审计。
     *
     * @param principal 当前认证主体
     * @param tool 当前处理的工具定义
     * @param agent 当前处理的 Agent 定义
     */
    private AgentDefinition revokeToolStateAndAudit(
            PrincipalRef principal,
            ToolDefinition tool,
            AgentDefinition agent
    ) {
        UUID tenantId = principal.tenantId();
        grantRepository.delete(tenantId, agent.id(), tool.id());
        AgentDefinition updated = agentRepository.removeToolFromAgent(tenantId, agent.id(), tool.id());
        auditAppender.append(
                tenantId,
                principal.principalId(),
                "TOOL_GRANT_REVOKE",
                "TOOL",
                tool.id().toString(),
                "SUCCEEDED",
                "已解除 Agent " + agent.id() + " 的工具授权"
        );
        return updated;
    }

    /**
     * 追加 Agent 创建成功审计事件。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param agent 当前处理的 Agent 定义。
     */
    private void appendAgentAudit(PrincipalRef principal, AgentDefinition agent) {
        auditAppender.append(principal.tenantId(), principal.principalId(), "AGENT_CREATE", "AGENT",
                agent.id().toString(), "SUCCEEDED", "Agent 创建成功");
    }

    /**
     * 在事务中保存工具、HTTP 配置和可选 MCP 发布状态。
     *
     * @param prepared 已完成治理校验的工具执行上下文。
     */
    private ToolDefinition saveToolWithHttpConfiguration(PreparedToolCreate prepared) {
        ToolDefinition saved = toolRepository.save(prepared.tool());
        if (prepared.httpToolConfig() == null) {
            return saved;
        }
        httpToolConfigRepository.save(prepared.httpToolConfig());
        if (prepared.mcpToolPublication() != null) {
            mcpToolPublicationRepository.save(prepared.mcpToolPublication());
        }
        return saved;
    }

    /**
     * 保存工具及附属配置，并在后续失败时执行补偿。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param prepared 已完成治理校验的工具执行上下文。
     */
    private ToolDefinition saveToolWithCompensation(PrincipalRef principal, PreparedToolCreate prepared) {
        boolean toolWriteAttempted = false;
        boolean configurationWriteAttempted = false;
        boolean publicationWriteAttempted = false;
        try {
            toolWriteAttempted = true;
            ToolDefinition saved = toolRepository.save(prepared.tool());
            if (prepared.httpToolConfig() != null) {
                configurationWriteAttempted = true;
                httpToolConfigRepository.save(prepared.httpToolConfig());
            }
            if (prepared.mcpToolPublication() != null) {
                publicationWriteAttempted = true;
                mcpToolPublicationRepository.save(prepared.mcpToolPublication());
            }
            appendToolAudit(principal, saved, prepared.mcpToolPublication() != null);
            return saved;
        } catch (RuntimeException exception) {
            compensateMemoryWrite(prepared, toolWriteAttempted, configurationWriteAttempted, publicationWriteAttempted, exception);
            throw exception;
        }
    }

    /**
     * 补偿内存模式下已经完成的部分写入。
     *
     * @param prepared 已完成治理校验的工具执行上下文。
     * @param toolWriteAttempted 是否已经尝试写入工具定义。
     * @param configurationWriteAttempted 是否已经尝试写入 HTTP 配置。
     * @param publicationWriteAttempted 是否已经尝试写入 MCP 发布状态。
     * @param original 执行变更前的原始状态，用于失败补偿。
     */
    private void compensateMemoryWrite(
            PreparedToolCreate prepared,
            boolean toolWriteAttempted,
            boolean configurationWriteAttempted,
            boolean publicationWriteAttempted,
            RuntimeException original
    ) {
        if (publicationWriteAttempted) {
            compensate(() -> mcpToolPublicationRepository.delete(prepared.tool().tenantId(), prepared.tool().id()), original);
        }
        if (configurationWriteAttempted) {
            compensate(() -> httpToolConfigRepository.delete(prepared.tool().tenantId(), prepared.tool().id()), original);
        }
        if (toolWriteAttempted) {
            compensate(() -> toolRepository.delete(prepared.tool().tenantId(), prepared.tool().id()), original);
        }
    }

    /**
     * 执行补偿动作，并把补偿失败附加到原异常。
     *
     * @param action 持有锁或执行补偿时调用的业务动作。
     * @param original 执行变更前的原始状态，用于失败补偿。
     */
    private void compensate(Runnable action, RuntimeException original) {
        try {
            action.run();
        } catch (RuntimeException compensationFailure) {
            original.addSuppressed(compensationFailure);
        }
    }

    /**
     * 校验输入数据及相关业务约束。
     *
     * @param type 待加入或校验的 Schema 值类型。
     * @param httpToolCreateSpec 可选的 HTTP 工具创建规格。
     * @param mcpPublished 是否同时发布为 MCP 工具。
     */
    private void validateToolCreateRequest(
            ToolType type,
            @Nullable HttpToolCreateSpec httpToolCreateSpec,
            boolean mcpPublished
    ) {
        if (type == ToolType.HTTP && httpToolCreateSpec == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HTTP 工具必须提供配置");
        }
        if (type != ToolType.HTTP && httpToolCreateSpec != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅 HTTP 工具可以提供 HTTP 配置");
        }
        if (type != ToolType.HTTP && mcpPublished) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅 HTTP 工具可以发布到 MCP");
        }
    }

    /**
     * 按删除前快照恢复工具及全部附属状态。
     *
     * @param snapshot 变更前的完整状态快照。
     * @param original 变更前的原始状态，用于失败补偿。
     */
    private void restoreToolState(ToolStateSnapshot snapshot, RuntimeException original) {
        compensate(() -> {
            if (toolRepository.findByTenantAndId(snapshot.tool().tenantId(), snapshot.tool().id()).isEmpty()) {
                if (!toolRepository.restoreDeletedToolForCompensation(snapshot.tool())) {
                    toolRepository.save(snapshot.tool());
                }
            } else {
                toolRepository.update(snapshot.tool());
            }
        }, original);
        compensate(() -> restoreHttpToolConfig(snapshot), original);
        compensate(() -> restoreMcpToolPublication(snapshot), original);
    }

    /**
     * 恢复工具原有的 HTTP 配置。
     *
     * @param snapshot 变更前的完整状态快照。
     */
    private void restoreHttpToolConfig(ToolStateSnapshot snapshot) {
        if (snapshot.httpToolConfig().isPresent()) {
            httpToolConfigRepository.save(snapshot.httpToolConfig().orElseThrow());
            return;
        }
        httpToolConfigRepository.delete(snapshot.tool().tenantId(), snapshot.tool().id());
    }

    /**
     * 恢复工具原有的 MCP 发布状态。
     *
     * @param snapshot 变更前的完整状态快照。
     */
    private void restoreMcpToolPublication(ToolStateSnapshot snapshot) {
        if (snapshot.mcpToolPublication().isPresent()) {
            mcpToolPublicationRepository.save(snapshot.mcpToolPublication().orElseThrow());
            return;
        }
        mcpToolPublicationRepository.delete(snapshot.tool().tenantId(), snapshot.tool().id());
    }

    /**
     * 恢复工具原有的授权集合。
     *
     * @param tool 当前处理的工具定义
     * @param grants 工具授权集合。
     * @param original 变更前的原始状态，用于失败补偿。
     */
    private void restoreToolGrants(
            ToolDefinition tool,
            List<ToolGrant> grants,
            RuntimeException original
    ) {
        compensate(() -> grantRepository.deleteByTenantAndToolId(tool.tenantId(), tool.id()), original);
        grants.forEach(grant -> compensate(() -> grantRepository.save(grant), original));
    }

    /**
     * 恢复 Agent 与工具的关联状态。
     *
     * @param agent 当前处理的 Agent 定义
     * @param toolId 目标工具标识
     * @param grants 工具授权集合。
     * @param original 变更前的原始状态，用于失败补偿。
     */
    private void restoreAgentToolState(
            AgentDefinition agent,
            UUID toolId,
            List<ToolGrant> grants,
            RuntimeException original
    ) {
        if (agent.toolIds().contains(toolId)) {
            compensate(() -> agentRepository.addToolToAgent(agent.tenantId(), agent.id(), toolId), original);
        } else {
            compensate(() -> agentRepository.removeToolFromAgent(agent.tenantId(), agent.id(), toolId), original);
        }
        compensate(() -> grantRepository.delete(agent.tenantId(), agent.id(), toolId), original);
        grants.forEach(grant -> compensate(() -> grantRepository.save(grant), original));
    }

    /**
     * 在当前租户中查询可见工具，不存在时抛出受控异常。
     *
     * @param tenantId 当前租户标识
     * @param toolId 目标工具标识
     */
    private ToolDefinition findTool(UUID tenantId, UUID toolId) {
        return toolRepository.findByTenantAndId(tenantId, toolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
    }

    /**
     * 加行锁查询当前租户的工具。
     *
     * @param tenantId 当前租户标识
     * @param toolId 目标工具标识
     */
    private ToolDefinition lockTool(UUID tenantId, UUID toolId) {
        return toolRepository.findByTenantAndIdForUpdate(tenantId, toolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
    }

    /**
     * 在当前租户中查询 Agent，不存在时抛出受控异常。
     *
     * @param tenantId 当前租户标识
     * @param agentId 目标 Agent 标识
     */
    private AgentDefinition findAgent(UUID tenantId, UUID agentId) {
        return agentRepository.findByTenantAndId(tenantId, agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
    }

    /**
     * 为工具更新流程创建稳定顺序的本地锁集合。
     */
    private static ReentrantLock[] createToolLocks() {
        ReentrantLock[] locks = new ReentrantLock[TOOL_LOCK_STRIPE_COUNT];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    /**
     * 获取指定租户和工具对应的进程内锁。
     *
     * @param tenantId 当前租户标识
     * @param toolId 目标工具标识
     */
    private static ReentrantLock toolLock(UUID tenantId, UUID toolId) {
        int stripe = Math.floorMod(Objects.hash(tenantId, toolId), TOOL_LOCK_STRIPE_COUNT);
        return TOOL_LOCKS[stripe];
    }

    /**
     * 获取指定租户和 Agent 对应的进程内锁。
     *
     * @param tenantId 当前租户标识
     * @param agentId 目标 Agent 标识
     */
    private static ReentrantLock agentLock(UUID tenantId, UUID agentId) {
        int stripe = Math.floorMod(Objects.hash(tenantId, agentId), TOOL_LOCK_STRIPE_COUNT);
        return AGENT_LOCKS[stripe];
    }

    /**
     * 持有工具锁执行回调，并保证退出时释放。
     *
     * @param tenantId 当前租户标识
     * @param toolId 目标工具标识
     * @param action 持有锁或执行补偿时调用的业务动作。
     */
    private static <T> T withToolLock(UUID tenantId, UUID toolId, Supplier<T> action) {
        ReentrantLock lock = toolLock(tenantId, toolId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 持有工具锁执行回调，并保证退出时释放。
     *
     * @param tenantId 当前租户标识
     * @param toolId 目标工具标识
     * @param action 持有锁或执行补偿时调用的业务动作。
     */
    private static void withToolLock(UUID tenantId, UUID toolId, Runnable action) {
        withToolLock(tenantId, toolId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 持有 Agent 锁执行回调，并保证退出时释放。
     *
     * @param tenantId 当前租户标识
     * @param agentId 目标 Agent 标识
     * @param action 持有锁或执行补偿时调用的业务动作。
     */
    private static <T> T withAgentLock(UUID tenantId, UUID agentId, Supplier<T> action) {
        ReentrantLock lock = agentLock(tenantId, agentId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 校验工具更新字段和类型相关约束。
     *
     * @param existing 变更前的现有领域对象。
     * @param spec 已校验的工具创建或更新规格。
     * @param currentlyPublished 工具在更新前是否处于 MCP 发布状态。
     */
    private void validateToolUpdateRequest(
            ToolDefinition existing,
            ToolUpdateSpec spec,
            boolean currentlyPublished
    ) {
        if (existing.type() != spec.type()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具类型不可修改");
        }
        if (existing.type() == ToolType.LOCAL && !existing.name().equals(spec.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "LOCAL 工具名称不可修改");
        }
        if (existing.type() == ToolType.HTTP && spec.httpToolCreateSpec() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HTTP 工具必须提供配置");
        }
        if (existing.type() != ToolType.HTTP && spec.httpToolCreateSpec() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅 HTTP 工具可以提供 HTTP 配置");
        }
        if (existing.type() == ToolType.LOCAL && spec.mcpPublished() && !currentlyPublished) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "未发布的 LOCAL 工具请使用 MCP 发布操作"
            );
        }
        if (existing.type() != ToolType.HTTP
                && existing.type() != ToolType.LOCAL
                && spec.mcpPublished()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅 HTTP 或 LOCAL 工具可以发布到 MCP");
        }
    }

    /**
     * 确保租户内没有占用同名工具的可见定义。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param name 目标对象的名称。
     */
    private void ensureToolNameAvailable(UUID tenantId, String name) {
        boolean nameExists = toolRepository.listByTenant(tenantId).stream()
                .anyMatch(existing -> existing.name().equals(name));
        if (nameExists) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前租户下工具名称已存在");
        }
    }

    /**
     * 确保租户内没有其他可见工具占用目标名称。
     *
     * @param tenantId 当前租户标识
     * @param toolId 目标工具标识
     * @param name 目标对象名称。
     */
    private void ensureToolNameAvailable(UUID tenantId, UUID toolId, String name) {
        boolean nameExists = toolRepository.listByTenant(tenantId).stream()
                .anyMatch(existing -> !existing.id().equals(toolId) && existing.name().equals(name));
        if (nameExists) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前租户下工具名称已存在");
        }
    }

    /**
     * 判断异常是否由工具名称唯一约束冲突引起。
     *
     * @param exception 当前捕获的异常，用于转换或记录失败信息。
     */
    private boolean isToolNameConflict(DuplicateKeyException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT)
                    .contains("ux_tool_definitions_tenant_name")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 追加工具创建审计；同时发布 MCP 时原子追加两条事件。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param tool 当前处理的工具定义。
     * @param mcpPublished 是否同时发布为 MCP 工具。
     */
    private void appendToolAudit(PrincipalRef principal, ToolDefinition tool, boolean mcpPublished) {
        if (mcpPublished) {
            auditAppender.appendAll(List.of(
                    new AuditAppender.AuditWrite(
                            principal.tenantId(), principal.principalId(), "TOOL_CREATE", "TOOL",
                            tool.id().toString(), "SUCCEEDED", "Tool 创建成功"
                    ),
                    new AuditAppender.AuditWrite(
                            principal.tenantId(), principal.principalId(), "MCP_TOOL_PUBLISHED", "TOOL",
                            tool.id().toString(), "SUCCEEDED", "MCP 工具已发布"
                    )
            ));
            return;
        }
        auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_CREATE", "TOOL",
                tool.id().toString(), "SUCCEEDED", "Tool 创建成功");
    }

    /**
     * 追加工具授权给 Agent 的成功审计事件。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param tool 当前处理的工具定义。
     * @param agent 当前处理的 Agent 定义。
     */
    private void appendGrantAudit(PrincipalRef principal, ToolDefinition tool, AgentDefinition agent) {
        auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_GRANT", "TOOL",
                tool.id().toString(), "SUCCEEDED", "Tool 已授权给 Agent " + agent.id());
    }

    /**
     * 追加工具更新及可选取消发布的审计事件。
     *
     * @param principal 当前认证主体
     * @param tool 当前处理的工具定义
     */
    private void appendToolUpdateAudit(PrincipalRef principal, ToolDefinition tool) {
        auditAppender.append(
                principal.tenantId(),
                principal.principalId(),
                "TOOL_UPDATE",
                "TOOL",
                tool.id().toString(),
                "SUCCEEDED",
                "工具更新成功"
        );
    }

    /**
     * 校验事务回调必须返回非空结果。
     *
     * @param result 上一步得到的处理结果。
     */
    private static <T> T requireResult(T result) {
        return Objects.requireNonNull(result, "事务未返回结果");
    }

    /**
     * 创建 {@code PreparedToolCreate} 实例并保存其运行所需依赖。
     */
    private record PreparedToolCreate(
            ToolDefinition tool,
            @Nullable HttpToolConfig httpToolConfig,
            @Nullable McpToolPublication mcpToolPublication
    ) {
    }

    private record PreparedToolUpdate(
            ToolDefinition originalTool,
            ToolDefinition tool,
            @Nullable HttpToolConfig httpToolConfig,
            @Nullable McpToolPublication mcpToolPublication,
            PublicationMutation publicationMutation,
            boolean mcpPublished
    ) {
    }

    private enum PublicationMutation {
        KEEP,
        UPSERT,
        DELETE
    }

    private record ToolStateSnapshot(
            ToolDefinition tool,
            Optional<HttpToolConfig> httpToolConfig,
            Optional<McpToolPublication> mcpToolPublication,
            List<ToolGrant> grants
    ) {
        /**
         * 校验并构造 {@code ToolStateSnapshot} 实例。
         *
         * @param tool 当前处理的工具定义
         * @param httpToolConfig 工具当前或目标 HTTP 配置。
         * @param mcpToolPublication 工具当前 MCP 发布记录。
         * @param grants 工具授权集合。
         */
        private ToolStateSnapshot {
            grants = List.copyOf(grants);
        }
    }

    /**
     * 工具更新命令中允许替换的字段。
     */
    public record ToolUpdateSpec(
            String name,
            String description,
            ToolType type,
            ToolRiskLevel riskLevel,
            boolean enabled,
            @Nullable HttpToolCreateSpec httpToolCreateSpec,
            boolean mcpPublished
    ) {
        /**
         * 校验并构造 {@code ToolUpdateSpec} 实例。
         *
         * @param name 目标对象名称。
         * @param description 目标对象说明。
         * @param type 目标工具类型。
         * @param riskLevel 目标工具风险等级。
         * @param enabled 是否启用目标能力。
         * @param httpToolCreateSpec 可选的 HTTP 工具创建规格。
         * @param mcpPublished 是否同时发布为 MCP 工具。
         */
        public ToolUpdateSpec {
            Objects.requireNonNull(name, "name 不能为空");
            Objects.requireNonNull(description, "description 不能为空");
            Objects.requireNonNull(type, "type 不能为空");
            Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
        }
    }

    /**
     * 工具更新命令提交的稳定快照，供 Controller 直接构造响应。
     */
    public record ToolUpdateResult(
            ToolDefinition tool,
            @Nullable HttpToolConfig httpToolConfig,
            boolean mcpPublished
    ) {
        /**
         * 校验并构造 {@code ToolUpdateResult} 实例。
         *
         * @param tool 当前处理的工具定义
         * @param httpToolConfig 工具当前或目标 HTTP 配置。
         * @param mcpPublished 是否同时发布为 MCP 工具。
         */
        public ToolUpdateResult {
            Objects.requireNonNull(tool, "tool 不能为空");
        }
    }
}
