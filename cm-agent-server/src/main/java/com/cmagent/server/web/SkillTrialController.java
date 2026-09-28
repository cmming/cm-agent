package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentProgressEvent;
import com.cmagent.core.domain.AgentTextDelta;
import com.cmagent.core.domain.SkillTrial;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.SkillTrialService;
import com.cmagent.server.runtime.ToolApprovalService;
import com.cmagent.server.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

/**
 * 指定技能版本的 TEST Run 接口。
 *
 * <p>试运行必须同时拥有技能写入和 Agent 运行权限：前者授权候选内容的治理操作，后者授权真实模型及
 * 工具副作用。租户、主体和权限均只从 JWT 会话取得，客户端请求不含也不能覆盖这些字段。
 * 路径参数显式声明名称，避免 Spring MVC 依赖编译器是否保留 Java 参数名。</p>
 */
@RestController
@RequestMapping("/api/skills/{skillId}/trials")
public class SkillTrialController {
    private final SkillTrialService trials;
    private final ToolApprovalService approvals;
    private final PermissionEvaluator permissions;
    private final AuditAppender audit;

    /** 创建试运行控制器。 */
    public SkillTrialController(
            SkillTrialService trials, ToolApprovalService approvals,
            PermissionEvaluator permissions, AuditAppender audit) {
        this.trials = trials;
        this.approvals = approvals;
        this.permissions = permissions;
        this.audit = audit;
    }

    /** 创建并同步执行一次指定版本试运行；调用方可使用返回的 runId 在刷新后继续查询。 */
    @PostMapping
    public ResponseEntity<SkillResponses.Trial> start(
            @PathVariable("skillId") UUID skillId,
            @Valid @RequestBody SkillRequests.TrialRequest request,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:write", "SKILL", skillId.toString());
        authorize(principal, "agent:run", "AGENT", request.agentId().toString());
        SkillTrial trial = trials.start(principal, skillId, request.versionId(), request.agentId(), request.input());
        trials.execute(principal, trial, request.input(), ignored -> { }, ignored -> { });
        return ResponseEntity.status(HttpStatus.CREATED).body(SkillResponses.Trial.from(trials.get(principal, trial.runId())));
    }

    /** 查询一个当前主体创建的试运行；跨租户或其他主体一律不暴露其存在性。 */
    @GetMapping("/{runId}")
    public SkillResponses.Trial get(
            @PathVariable("skillId") UUID skillId,
            @PathVariable("runId") UUID runId,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:read", "SKILL", skillId.toString());
        SkillTrial trial = trials.get(principal, runId);
        if (!trial.skillId().equals(skillId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "技能试运行不存在");
        }
        return SkillResponses.Trial.from(trial);
    }

    /** 刷新后读取当前 TEST Run 待处理的运行级审批；没有待审批时返回空响应。 */
    @GetMapping("/{runId}/approvals/current")
    public ResponseEntity<ToolApprovalService.ToolApprovalView> currentApproval(
            @PathVariable("skillId") UUID skillId,
            @PathVariable("runId") UUID runId,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:read", "SKILL", skillId.toString());
        SkillTrial trial = trials.get(principal, runId);
        if (!trial.skillId().equals(skillId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "技能试运行不存在");
        }
        authorize(principal, "agent:read", "AGENT", trial.agentId().toString());
        ToolApprovalService.ToolApprovalView approval = approvals.currentPendingForRun(principal, trial.agentId(), runId);
        return approval == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(approval);
    }

    /** 对 TEST Run 的运行级审批提交决定并恢复同一 Run。 */
    @PostMapping("/{runId}/approvals/{approvalId}/decisions")
    public TrialApprovalResponse decide(
            @PathVariable("skillId") UUID skillId,
            @PathVariable("runId") UUID runId,
            @PathVariable("approvalId") UUID approvalId,
            @Valid @RequestBody SkillRequests.TrialApprovalDecisionRequest request,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "skill:write", "SKILL", skillId.toString());
        SkillTrial trial = trials.get(principal, runId);
        if (!trial.skillId().equals(skillId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "技能试运行不存在");
        }
        authorize(principal, "agent:run", "AGENT", trial.agentId().toString());
        authorize(principal, "agent:approve", "AGENT", trial.agentId().toString());
        try {
            ToolApprovalService.RunApprovalResumeOutcome outcome = approvals.decideAndResumeRun(
                    principal, trial.agentId(), runId, approvalId, request.expectedVersion(),
                    request.items().stream().map(item -> new ToolApprovalService.ItemDecision(item.itemId(), item.decision())).toList(),
                    ignored -> { }, ignored -> { });
            SkillTrial refreshed = trials.finalizeResult(principal, runId, outcome.run());
            return new TrialApprovalResponse(SkillResponses.Trial.from(refreshed), outcome.decision(), outcome.nextApproval());
        } catch (ToolApprovalService.ApprovalCreationFailure failure) {
            // 只有下一轮审批事实创建失败才由服务关闭 Run；此处同步收口其关联试运行。
            trials.markFailed(principal, runId);
            throw failure;
        }
    }

    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(),
                Set.copyOf(session.permissions()));
    }

    private void authorize(PrincipalRef principal, String permission, String resourceType, String resourceId) {
        AuthorizationDecision decision = permissions.check(principal, permission);
        if (!decision.allowed()) {
            audit.accessDenied(principal, resourceType, resourceId, permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    /** 运行级审批恢复后的试运行和审批状态。 */
    public record TrialApprovalResponse(
            SkillResponses.Trial trial,
            ToolApprovalService.ToolApprovalView decision,
            ToolApprovalService.ToolApprovalView nextApproval
    ) {
    }
}
