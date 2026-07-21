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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ManagementCommandService {
    private static final UUID MODEL_PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private final AgentDefinitionRepository agentRepository;
    private final ToolDefinitionRepository toolRepository;
    private final HttpToolConfigRepository httpToolConfigRepository;
    private final McpToolPublicationRepository mcpToolPublicationRepository;
    private final ToolGrantRepository grantRepository;
    private final AuditAppender auditAppender;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public ManagementCommandService(
            AgentDefinitionRepository agentRepository,
            ToolDefinitionRepository toolRepository,
            HttpToolConfigRepository httpToolConfigRepository,
            McpToolPublicationRepository mcpToolPublicationRepository,
            ToolGrantRepository grantRepository,
            AuditAppender auditAppender,
            @Nullable TransactionTemplate transactionTemplate
    ) {
        this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository 不能为空");
        this.toolRepository = Objects.requireNonNull(toolRepository, "toolRepository 不能为空");
        this.httpToolConfigRepository = Objects.requireNonNull(httpToolConfigRepository, "httpToolConfigRepository 不能为空");
        this.mcpToolPublicationRepository = Objects.requireNonNull(mcpToolPublicationRepository, "mcpToolPublicationRepository 不能为空");
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository 不能为空");
        this.auditAppender = Objects.requireNonNull(auditAppender, "auditAppender 不能为空");
        this.transactionTemplate = transactionTemplate;
    }

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

    public ToolDefinition createTool(
            PrincipalRef principal, String name, String description, ToolType type, ToolRiskLevel riskLevel
    ) {
        return createTool(principal, name, description, type, riskLevel, null, false);
    }

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
        String inputSchema = httpToolCreateSpec == null ? "{\"type\":\"object\"}" : httpToolCreateSpec.inputSchema();
        String endpoint = httpToolCreateSpec == null ? "" : httpToolCreateSpec.urlTemplate();
        ToolDefinition tool = new ToolDefinition(
                UUID.randomUUID(), principal.tenantId(), name, description, type, inputSchema,
                riskLevel, true, endpoint, principal.principalId(), principal.principalId()
        );
        if (transactionTemplate == null) {
            appendToolAudit(principal, tool);
            return saveToolWithHttpConfiguration(principal, tool, httpToolCreateSpec, mcpPublished);
        }
        return requireResult(transactionTemplate.execute(status -> {
            ToolDefinition saved = saveToolWithHttpConfiguration(principal, tool, httpToolCreateSpec, mcpPublished);
            appendToolAudit(principal, saved);
            return saved;
        }));
    }

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

    private ToolDefinition saveToolWithHttpConfiguration(
            PrincipalRef principal,
            ToolDefinition tool,
            @Nullable HttpToolCreateSpec httpToolCreateSpec,
            boolean mcpPublished
    ) {
        ToolDefinition saved = toolRepository.save(tool);
        if (httpToolCreateSpec == null) {
            return saved;
        }
        httpToolConfigRepository.save(new HttpToolConfig(
                principal.tenantId(),
                saved.id(),
                httpToolCreateSpec.method(),
                httpToolCreateSpec.urlTemplate(),
                httpToolCreateSpec.inputSchema(),
                httpToolCreateSpec.parameterMappings(),
                httpToolCreateSpec.secretHeaders(),
                httpToolCreateSpec.timeout()
        ));
        if (mcpPublished) {
            mcpToolPublicationRepository.save(new McpToolPublication(
                    principal.tenantId(), saved.id(), true, principal.principalId()
            ));
        }
        return saved;
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

    private void appendToolAudit(PrincipalRef principal, ToolDefinition tool) {
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
}
