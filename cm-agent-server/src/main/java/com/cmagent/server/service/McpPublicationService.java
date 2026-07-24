package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.McpToolPublication;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.tool.ToolRegistry;
import com.cmagent.server.audit.AuditAppender;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
/** 管理工具的 MCP 发布状态，并保证租户、工具名称和注册信息的一致性。 */
public class McpPublicationService {
    private final ToolDefinitionRepository toolRepository;
    private final HttpToolConfigRepository httpToolConfigRepository;
    private final McpToolPublicationRepository publicationRepository;
    private final ToolRegistry registry;
    private final AuditAppender auditAppender;
    private final TransactionTemplate transactionTemplate;

    public McpPublicationService(
            ToolDefinitionRepository toolRepository,
            HttpToolConfigRepository httpToolConfigRepository,
            McpToolPublicationRepository publicationRepository,
            ToolRegistry registry,
            AuditAppender auditAppender,
            @Nullable TransactionTemplate transactionTemplate
    ) {
        this.toolRepository = Objects.requireNonNull(toolRepository, "toolRepository 不能为空");
        this.httpToolConfigRepository = Objects.requireNonNull(httpToolConfigRepository, "httpToolConfigRepository 不能为空");
        this.publicationRepository = Objects.requireNonNull(publicationRepository, "publicationRepository 不能为空");
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.auditAppender = Objects.requireNonNull(auditAppender, "auditAppender 不能为空");
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 发布工具到 MCP 目录，并记录发布审计。
     *
     * @param principal 当前认证主体
     * @param toolId    待发布工具标识
     * @return 已保存的 MCP 发布记录
     * @throws ResponseStatusException 工具不可见、配置不合法或名称冲突时抛出
     * @throws RuntimeException        发布或审计失败时抛出
     */
    public McpToolPublication publish(PrincipalRef principal, UUID toolId) {
        // 发布前重新读取并校验工具，避免使用过期的控制台数据绕过治理规则。
        ToolDefinition tool = findVisibleTool(principal, toolId);
        validatePublishable(tool);
        rejectConflictingEnabledName(tool);
        McpToolPublication publication = new McpToolPublication(principal.tenantId(), tool.id(), true, principal.principalId());
        if (transactionTemplate != null) {
            return Objects.requireNonNull(transactionTemplate.execute(status -> publishAndAudit(principal, publication)));
        }
        Optional<McpToolPublication> previous = publicationRepository.findByTenantAndToolId(principal.tenantId(), tool.id());
        try {
            return publishAndAudit(principal, publication);
        } catch (RuntimeException failure) {
            restore(principal.tenantId(), tool.id(), previous);
            throw failure;
        }
    }

    /**
     * 取消工具的 MCP 发布状态，并记录取消发布审计。
     *
     * @param principal 当前认证主体
     * @param toolId    待取消发布工具标识
     * @throws ResponseStatusException 工具不可见时抛出
     * @throws RuntimeException        取消发布或审计失败时抛出
     */
    public void unpublish(PrincipalRef principal, UUID toolId) {
        // 取消发布与发布使用相同的租户边界，并在事务不可用时执行补偿恢复。
        ToolDefinition tool = findVisibleTool(principal, toolId);
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(status -> unpublishAndAudit(principal, tool));
            return;
        }
        Optional<McpToolPublication> previous = publicationRepository.findByTenantAndToolId(principal.tenantId(), tool.id());
        try {
            unpublishAndAudit(principal, tool);
        } catch (RuntimeException failure) {
            restore(principal.tenantId(), tool.id(), previous);
            throw failure;
        }
    }

    private McpToolPublication publishAndAudit(PrincipalRef principal, McpToolPublication publication) {
        McpToolPublication saved = publicationRepository.save(publication);
        auditAppender.append(principal.tenantId(), principal.principalId(), "MCP_TOOL_PUBLISHED", "TOOL",
                publication.toolId().toString(), "SUCCEEDED", "MCP 工具已发布");
        return saved;
    }

    private void unpublishAndAudit(PrincipalRef principal, ToolDefinition tool) {
        publicationRepository.delete(principal.tenantId(), tool.id());
        auditAppender.append(principal.tenantId(), principal.principalId(), "MCP_TOOL_UNPUBLISHED", "TOOL",
                tool.id().toString(), "SUCCEEDED", "MCP 工具已取消发布");
    }

    private ToolDefinition findVisibleTool(PrincipalRef principal, UUID toolId) {
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(toolId, "toolId 不能为空");
        return toolRepository.findByTenantAndId(principal.tenantId(), toolId)
                .filter(tool -> tool.enabled() && principal.tenantId().equals(tool.tenantId()) && toolId.equals(tool.id()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
    }

    private void validatePublishable(ToolDefinition tool) {
        if (tool.type() == ToolType.HTTP) {
            HttpToolConfig config = httpToolConfigRepository.findByTenantAndToolId(tool.tenantId(), tool.id()).orElse(null);
            McpToolPublicationRules.validateHttp(tool, config);
            return;
        }
        McpToolPublicationRules.validateName(tool.name());
        if (tool.type() == ToolType.LOCAL && isSameRegistration(tool)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具不可发布");
    }

    private void rejectConflictingEnabledName(ToolDefinition tool) {
        // MCP 客户端按名称发现工具；同一租户内启用工具不能出现重名。
        Map<UUID, ToolDefinition> tools = toolRepository.listByTenant(tool.tenantId()).stream()
                .collect(java.util.stream.Collectors.toMap(ToolDefinition::id, candidate -> candidate));
        boolean conflict = publicationRepository.listEnabledByTenant(tool.tenantId()).stream()
                .filter(publication -> !publication.toolId().equals(tool.id()))
                .map(publication -> tools.get(publication.toolId()))
                .filter(Objects::nonNull)
                .anyMatch(existing -> tool.name().equals(existing.name()));
        if (conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MCP 工具名称已发布");
        }
    }

    private boolean isSameRegistration(ToolDefinition tool) {
        return registry.snapshot(tool.id())
                .map(ToolRegistry.ToolRegistrationSnapshot::definition)
                .map(registered -> tool.tenantId().equals(registered.tenantId())
                        && tool.id().equals(registered.id())
                        && tool.name().equals(registered.name()))
                .orElse(false);
    }

    private void restore(UUID tenantId, UUID toolId, Optional<McpToolPublication> previous) {
        if (previous.isPresent()) {
            publicationRepository.save(previous.get());
        } else {
            publicationRepository.delete(tenantId, toolId);
        }
    }
}
