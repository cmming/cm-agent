package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.AgentDefinitionRepository;
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
    private final ToolGrantRepository grantRepository;
    private final AuditAppender auditAppender;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public ManagementCommandService(
            AgentDefinitionRepository agentRepository,
            ToolDefinitionRepository toolRepository,
            ToolGrantRepository grantRepository,
            AuditAppender auditAppender,
            @Nullable TransactionTemplate transactionTemplate
    ) {
        this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository 不能为空");
        this.toolRepository = Objects.requireNonNull(toolRepository, "toolRepository 不能为空");
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
        ToolDefinition tool = new ToolDefinition(
                UUID.randomUUID(), principal.tenantId(), name, description, type, "{\"type\":\"object\"}",
                riskLevel, true, "", principal.principalId(), principal.principalId()
        );
        if (transactionTemplate == null) {
            appendToolAudit(principal, tool);
            return toolRepository.save(tool);
        }
        return requireResult(transactionTemplate.execute(status -> {
            ToolDefinition saved = toolRepository.save(tool);
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
