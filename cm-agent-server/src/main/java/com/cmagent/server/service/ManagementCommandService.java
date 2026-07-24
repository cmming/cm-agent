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
import java.util.UUID;

@Service
/** 管理类写操作的统一编排层，负责校验、持久化和必要的审计动作。 */
public class ManagementCommandService {
    private static final UUID MODEL_PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private final AgentDefinitionRepository agentRepository;
    private final ToolDefinitionRepository toolRepository;
    private final HttpToolConfigRepository httpToolConfigRepository;
    private final McpToolPublicationRepository mcpToolPublicationRepository;
    private final ToolGrantRepository grantRepository;
    private final AuditAppender auditAppender;
    private final HttpToolConfigValidator httpToolConfigValidator;
    private final TransactionTemplate transactionTemplate;

    @Autowired
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
        ToolDefinition tool = toolRepository.findByTenantAndId(principal.tenantId(), toolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
        AgentDefinition agent = agentRepository.findByTenantAndId(principal.tenantId(), agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
        ToolGrant grant = new ToolGrant(principal.tenantId(), tool.id(), agent.id(), null, true);
        if (transactionTemplate == null) {
            appendGrantAudit(principal, tool, agent);
            ToolGrant saved = grantRepository.save(grant);
            agentRepository.addToolToAgent(principal.tenantId(), agent.id(), tool.id());
            return saved;
        }
        return requireResult(transactionTemplate.execute(status -> {
            ToolGrant saved = grantRepository.save(grant);
            agentRepository.addToolToAgent(principal.tenantId(), agent.id(), tool.id());
            appendGrantAudit(principal, tool, agent);
            return saved;
        }));
    }

    private void appendAgentAudit(PrincipalRef principal, AgentDefinition agent) {
        auditAppender.append(principal.tenantId(), principal.principalId(), "AGENT_CREATE", "AGENT",
                agent.id().toString(), "SUCCEEDED", "Agent 创建成功");
    }

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

    private void compensate(Runnable action, RuntimeException original) {
        try {
            action.run();
        } catch (RuntimeException compensationFailure) {
            original.addSuppressed(compensationFailure);
        }
    }

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

    private void ensureToolNameAvailable(UUID tenantId, String name) {
        boolean nameExists = toolRepository.listByTenant(tenantId).stream()
                .anyMatch(existing -> existing.name().equals(name));
        if (nameExists) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前租户下工具名称已存在");
        }
    }

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

    private void appendGrantAudit(PrincipalRef principal, ToolDefinition tool, AgentDefinition agent) {
        auditAppender.append(principal.tenantId(), principal.principalId(), "TOOL_GRANT", "TOOL",
                tool.id().toString(), "SUCCEEDED", "Tool 已授权给 Agent " + agent.id());
    }

    private static <T> T requireResult(T result) {
        return Objects.requireNonNull(result, "事务未返回结果");
    }

    private record PreparedToolCreate(
            ToolDefinition tool,
            @Nullable HttpToolConfig httpToolConfig,
            @Nullable McpToolPublication mcpToolPublication
    ) {
    }
}
