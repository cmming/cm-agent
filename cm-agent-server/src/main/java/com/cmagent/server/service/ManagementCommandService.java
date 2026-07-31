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
     * ManagementCommandService：处理该类内部的业务逻辑或辅助计算。
     *
     * @param agentRepository 参与 ManagementCommandService 处理的 agentRepository 输入值。
     * @param toolRepository 参与 ManagementCommandService 处理的 toolRepository 输入值。
     * @param httpToolConfigRepository 参与 ManagementCommandService 处理的 httpToolConfigRepository 输入值。
     * @param mcpToolPublicationRepository 参与 ManagementCommandService 处理的 mcpToolPublicationRepository 输入值。
     * @param grantRepository 参与 ManagementCommandService 处理的 grantRepository 输入值。
     * @param auditAppender 参与 ManagementCommandService 处理的 auditAppender 输入值。
     * @param httpToolConfigValidator 参与 ManagementCommandService 处理的 httpToolConfigValidator 输入值。
     * @param transactionTemplate 参与 ManagementCommandService 处理的 transactionTemplate 输入值。
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
     * prepareToolCreate：处理该类内部的业务逻辑或辅助计算。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param name 目标对象的名称。
     * @param description 参与 prepareToolCreate 处理的 description 输入值。
     * @param type 参与 prepareToolCreate 处理的 type 输入值。
     * @param riskLevel 参与 prepareToolCreate 处理的 riskLevel 输入值。
     * @param httpToolCreateSpec 参与 prepareToolCreate 处理的 httpToolCreateSpec 输入值。
     * @param mcpPublished 参与 prepareToolCreate 处理的 mcpPublished 输入值。
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
        return withToolLock(
                principal.tenantId(),
                toolId,
                () -> grantToolWithLock(principal, toolId, agentId)
        );
    }

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
     * @return 更新后的工具定义
     * @throws ResponseStatusException 工具不存在、不可变字段被修改或名称冲突时抛出
     */
    public ToolDefinition updateTool(PrincipalRef principal, UUID toolId, ToolUpdateSpec spec) {
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
     * @throws ResponseStatusException 工具不存在或仍被 Agent 引用时抛出
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
        return withToolLock(principal.tenantId(), toolId, () -> {
            if (transactionTemplate == null) {
                return revokeToolWithCompensation(principal, toolId, agentId);
            }
            return requireResult(transactionTemplate.execute(
                    status -> revokeToolAndAudit(principal, toolId, agentId)
            ));
        });
    }

    private ToolDefinition updateToolAndAudit(PrincipalRef principal, UUID toolId, ToolUpdateSpec spec) {
        PreparedToolUpdate prepared = prepareToolUpdateCommand(principal, toolId, spec);
        return applyToolUpdateAndAudit(principal, prepared);
    }

    private ToolDefinition updateToolWithCompensation(PrincipalRef principal, UUID toolId, ToolUpdateSpec spec) {
        PreparedToolUpdate prepared = prepareToolUpdateCommand(principal, toolId, spec);
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

    private PreparedToolUpdate prepareToolUpdateCommand(
            PrincipalRef principal,
            UUID toolId,
            ToolUpdateSpec spec
    ) {
        ToolDefinition existing = toolRepository.findByTenantAndId(principal.tenantId(), toolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
        validateToolUpdateRequest(existing, spec);
        ensureToolNameAvailable(principal.tenantId(), toolId, spec.name());
        return prepareToolUpdate(principal, existing, spec);
    }

    private ToolDefinition applyToolUpdateAndAudit(PrincipalRef principal, PreparedToolUpdate prepared) {
        UUID tenantId = principal.tenantId();
        ToolDefinition updated = toolRepository.update(prepared.tool());
        if (prepared.httpToolConfig() != null) {
            httpToolConfigRepository.save(prepared.httpToolConfig());
            if (prepared.mcpToolPublication() == null) {
                mcpToolPublicationRepository.delete(tenantId, prepared.tool().id());
            } else {
                mcpToolPublicationRepository.save(prepared.mcpToolPublication());
            }
        }
        appendToolUpdateAudit(principal, updated);
        return updated;
    }

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
            return new PreparedToolUpdate(existing, updated, null, null);
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
        return new PreparedToolUpdate(existing, updated, configuration, publication);
    }

    private void deleteToolAndAudit(PrincipalRef principal, UUID toolId) {
        UUID tenantId = principal.tenantId();
        ToolDefinition tool = lockTool(tenantId, toolId);
        validateToolNotReferenced(tenantId, toolId);
        deleteToolStateAndAudit(principal, tool);
    }

    private void deleteToolWithCompensation(PrincipalRef principal, UUID toolId) {
        UUID tenantId = principal.tenantId();
        ToolDefinition tool = toolRepository.findByTenantAndId(tenantId, toolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
        validateToolNotReferenced(tenantId, toolId);
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
            restoreToolState(snapshot, exception);
            restoreToolGrants(snapshot.tool(), snapshot.grants(), exception);
            throw exception;
        }
    }

    private void validateToolNotReferenced(UUID tenantId, UUID toolId) {
        boolean referenced = agentRepository.listByTenant(tenantId).stream()
                .anyMatch(agent -> agent.toolIds().contains(toolId));
        if (referenced) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "工具仍被 Agent 关联，请先解除关联后再删除"
            );
        }
    }

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

    private AgentDefinition revokeToolAndAudit(PrincipalRef principal, UUID toolId, UUID agentId) {
        UUID tenantId = principal.tenantId();
        ToolDefinition tool = toolRepository.findByTenantAndId(tenantId, toolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
        AgentDefinition agent = agentRepository.findByTenantAndId(tenantId, agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
        return revokeToolStateAndAudit(principal, tool, agent);
    }

    private AgentDefinition revokeToolWithCompensation(PrincipalRef principal, UUID toolId, UUID agentId) {
        UUID tenantId = principal.tenantId();
        ToolDefinition tool = toolRepository.findByTenantAndId(tenantId, toolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
        AgentDefinition agent = agentRepository.findByTenantAndId(tenantId, agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
        List<ToolGrant> grants = grantRepository.listByTenantAgentAndTool(tenantId, agentId, toolId);
        try {
            return revokeToolStateAndAudit(principal, tool, agent);
        } catch (RuntimeException exception) {
            restoreAgentToolState(agent, tool.id(), grants, exception);
            throw exception;
        }
    }

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
     * appendAgentAudit：追加处理结果或审计记录。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param agent 当前处理的 Agent 定义。
     */
    private void appendAgentAudit(PrincipalRef principal, AgentDefinition agent) {
        auditAppender.append(principal.tenantId(), principal.principalId(), "AGENT_CREATE", "AGENT",
                agent.id().toString(), "SUCCEEDED", "Agent 创建成功");
    }

    /**
     * saveToolWithHttpConfiguration：保存当前对象及其关联配置。
     *
     * @param prepared 参与 saveToolWithHttpConfiguration 处理的 prepared 输入值。
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
     * saveToolWithCompensation：保存当前对象及其关联配置。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param prepared 参与 saveToolWithCompensation 处理的 prepared 输入值。
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
     * compensateMemoryWrite：处理该类内部的业务逻辑或辅助计算。
     *
     * @param prepared 参与 compensateMemoryWrite 处理的 prepared 输入值。
     * @param toolWriteAttempted 参与 compensateMemoryWrite 处理的 toolWriteAttempted 输入值。
     * @param configurationWriteAttempted 参与 compensateMemoryWrite 处理的 configurationWriteAttempted 输入值。
     * @param publicationWriteAttempted 参与 compensateMemoryWrite 处理的 publicationWriteAttempted 输入值。
     * @param original 参与 compensateMemoryWrite 处理的 original 输入值。
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
     * compensate：处理该类内部的业务逻辑或辅助计算。
     *
     * @param action 参与 compensate 处理的 action 输入值。
     * @param original 参与 compensate 处理的 original 输入值。
     */
    private void compensate(Runnable action, RuntimeException original) {
        try {
            action.run();
        } catch (RuntimeException compensationFailure) {
            original.addSuppressed(compensationFailure);
        }
    }

    /**
     * validateToolCreateRequest：校验输入、状态或前置条件。
     *
     * @param type 参与 validateToolCreateRequest 处理的 type 输入值。
     * @param httpToolCreateSpec 参与 validateToolCreateRequest 处理的 httpToolCreateSpec 输入值。
     * @param mcpPublished 参与 validateToolCreateRequest 处理的 mcpPublished 输入值。
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

    private void restoreToolState(ToolStateSnapshot snapshot, RuntimeException original) {
        compensate(() -> {
            if (toolRepository.findByTenantAndId(snapshot.tool().tenantId(), snapshot.tool().id()).isEmpty()) {
                toolRepository.save(snapshot.tool());
            } else {
                toolRepository.update(snapshot.tool());
            }
        }, original);
        compensate(() -> restoreHttpToolConfig(snapshot), original);
        compensate(() -> restoreMcpToolPublication(snapshot), original);
    }

    private void restoreHttpToolConfig(ToolStateSnapshot snapshot) {
        if (snapshot.httpToolConfig().isPresent()) {
            httpToolConfigRepository.save(snapshot.httpToolConfig().orElseThrow());
            return;
        }
        httpToolConfigRepository.delete(snapshot.tool().tenantId(), snapshot.tool().id());
    }

    private void restoreMcpToolPublication(ToolStateSnapshot snapshot) {
        if (snapshot.mcpToolPublication().isPresent()) {
            mcpToolPublicationRepository.save(snapshot.mcpToolPublication().orElseThrow());
            return;
        }
        mcpToolPublicationRepository.delete(snapshot.tool().tenantId(), snapshot.tool().id());
    }

    private void restoreToolGrants(
            ToolDefinition tool,
            List<ToolGrant> grants,
            RuntimeException original
    ) {
        compensate(() -> grantRepository.deleteByTenantAndToolId(tool.tenantId(), tool.id()), original);
        grants.forEach(grant -> compensate(() -> grantRepository.save(grant), original));
    }

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

    private ToolDefinition findTool(UUID tenantId, UUID toolId) {
        return toolRepository.findByTenantAndId(tenantId, toolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
    }

    private ToolDefinition lockTool(UUID tenantId, UUID toolId) {
        return toolRepository.findByTenantAndIdForUpdate(tenantId, toolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
    }

    private AgentDefinition findAgent(UUID tenantId, UUID agentId) {
        return agentRepository.findByTenantAndId(tenantId, agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
    }

    private static ReentrantLock[] createToolLocks() {
        ReentrantLock[] locks = new ReentrantLock[TOOL_LOCK_STRIPE_COUNT];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private static ReentrantLock toolLock(UUID tenantId, UUID toolId) {
        int stripe = Math.floorMod(Objects.hash(tenantId, toolId), TOOL_LOCK_STRIPE_COUNT);
        return TOOL_LOCKS[stripe];
    }

    private static <T> T withToolLock(UUID tenantId, UUID toolId, Supplier<T> action) {
        ReentrantLock lock = toolLock(tenantId, toolId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private static void withToolLock(UUID tenantId, UUID toolId, Runnable action) {
        withToolLock(tenantId, toolId, () -> {
            action.run();
            return null;
        });
    }

    private void validateToolUpdateRequest(ToolDefinition existing, ToolUpdateSpec spec) {
        if (existing.type() != spec.type()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具类型不可修改");
        }
        if (existing.type() == ToolType.LOCAL && !existing.name().equals(spec.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "LOCAL 工具名称不可修改");
        }
        validateToolCreateRequest(existing.type(), spec.httpToolCreateSpec(), spec.mcpPublished());
    }

    /**
     * ensureToolNameAvailable：校验输入、状态或前置条件。
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

    private void ensureToolNameAvailable(UUID tenantId, UUID toolId, String name) {
        boolean nameExists = toolRepository.listByTenant(tenantId).stream()
                .anyMatch(existing -> !existing.id().equals(toolId) && existing.name().equals(name));
        if (nameExists) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前租户下工具名称已存在");
        }
    }

    /**
     * isToolNameConflict：判断当前条件是否成立。
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
     * appendToolAudit：追加处理结果或审计记录。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param tool 当前处理的工具定义。
     * @param mcpPublished 参与 appendToolAudit 处理的 mcpPublished 输入值。
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
     * appendGrantAudit：追加处理结果或审计记录。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param tool 当前处理的工具定义。
     * @param agent 当前处理的 Agent 定义。
     */
    private void appendGrantAudit(PrincipalRef principal, ToolDefinition tool, AgentDefinition agent) {
        auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_GRANT", "TOOL",
                tool.id().toString(), "SUCCEEDED", "Tool 已授权给 Agent " + agent.id());
    }

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
     * requireResult：校验输入、状态或前置条件。
     *
     * @param result 参与 requireResult 处理的 result 输入值。
     */
    private static <T> T requireResult(T result) {
        return Objects.requireNonNull(result, "事务未返回结果");
    }

    /**
     * PreparedToolCreate：不可变数据载体，用于在本模块内传递结构化信息。
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
            @Nullable McpToolPublication mcpToolPublication
    ) {
    }

    private record ToolStateSnapshot(
            ToolDefinition tool,
            Optional<HttpToolConfig> httpToolConfig,
            Optional<McpToolPublication> mcpToolPublication,
            List<ToolGrant> grants
    ) {
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
        public ToolUpdateSpec {
            Objects.requireNonNull(name, "name 不能为空");
            Objects.requireNonNull(description, "description 不能为空");
            Objects.requireNonNull(type, "type 不能为空");
            Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
        }
    }
}
