package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.WorkspaceSelection;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.ConversationService;
import com.cmagent.server.runtime.WorkspaceSelectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

/** 目录只来自受信部署登记；HTTP 层不接受 tenant、绝对路径或文件清单。 */
@RestController
@RequestMapping("/api/agents/{agentId}")
public final class WorkspaceSelectionController {
    /** 权限校验后才允许读取目录名称及根内位置。 */
    private final PermissionEvaluator permissions;
    /** 拒绝必须进入严格审计，不能用普通日志替代。 */
    private final AuditAppender audit;
    /** 目录和绑定元数据编排。 */
    private final WorkspaceSelectionService selections;
    /** 复用现有会话读取权限边界及首发控制锁。 */
    private final ConversationService conversations;

    public WorkspaceSelectionController(PermissionEvaluator permissions, AuditAppender audit,
            WorkspaceSelectionService selections, ConversationService conversations) {
        this.permissions = permissions; this.audit = audit; this.selections = selections; this.conversations = conversations;
    }

    /** 仅显示当前认证租户、Agent 且同时有读/运行权限的有界登记清单。 */
    @GetMapping("/workspaces")
    public WorkspaceSelectionService.Catalog catalog(@PathVariable("agentId") UUID agentId, Authentication authentication) {
        var principal = principal(authentication);
        authorize(principal, "agent:read", agentId);
        authorize(principal, "agent:run", agentId);
        return selections.catalog(principal, agentId);
    }

    /** 无绑定的历史会话按 DEFAULT 视图返回，不补写或推断历史。 */
    @GetMapping("/conversations/{conversationId}/workspace")
    public WorkspaceSelectionService.View get(@PathVariable("agentId") UUID agentId, @PathVariable("conversationId") UUID conversationId,
            Authentication authentication) {
        var principal = principal(authentication);
        authorize(principal, "agent:read", agentId);
        return selections.describe(principal, conversations.get(principal, agentId, conversationId));
    }

    /** 版本条件和创建者/空会话限制在锁内验证；写入失败不能自动重试。 */
    @PutMapping("/conversations/{conversationId}/workspace")
    public WorkspaceSelectionService.View update(@PathVariable("agentId") UUID agentId, @PathVariable("conversationId") UUID conversationId,
            @Valid @RequestBody UpdateRequest request, Authentication authentication) {
        var principal = principal(authentication);
        authorize(principal, "agent:run", agentId);
        return conversations.updateWorkspace(principal, agentId, conversationId, request.workspaceSelection(), request.expectedVersion());
    }

    /** 会话 Run 视图先检查资源关联，不能仅凭同租户 Run 标识读取。 */
    @GetMapping("/conversations/{conversationId}/runs/{runId}/workspace")
    public WorkspaceSelectionService.View run(@PathVariable("agentId") UUID agentId, @PathVariable("conversationId") UUID conversationId,
            @PathVariable("runId") UUID runId, Authentication authentication) {
        var principal = principal(authentication);
        authorize(principal, "agent:read", agentId);
        conversations.run(principal, agentId, conversationId, runId);
        return selections.describeRun(principal.tenantId(), agentId, runId);
    }

    private PrincipalRef principal(Authentication authentication) {
        // JWT 过滤器放入的是已验证 JwtSession，不能把 HTTP 传入的 tenant 当作认证主体。
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof com.cmagent.server.security.JwtService.JwtSession session))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return new PrincipalRef(session.tenantId(),session.principalId(),session.displayName(),java.util.Set.copyOf(session.permissions()));
    }
    private void authorize(PrincipalRef principal, String permission, UUID agent) {
        var decision = permissions.check(principal, permission);
        if (!decision.allowed()) {
            audit.accessDenied(principal, "AGENT", agent.toString(), permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    /**
     * @param workspaceSelection 显式三态选择
     * @param expectedVersion 页面最后读取的绑定版本 */
    public record UpdateRequest(@NotNull WorkspaceSelection workspaceSelection, @Min(0) long expectedVersion) {}
}
