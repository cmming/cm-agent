package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunPageRequest;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunToolCall;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.RunExecutionService;
import com.cmagent.server.runtime.RunPersistenceService;
import com.cmagent.server.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/agents/{agentId}/runs")
/** Agent 运行接口，负责校验主体权限并委托运行服务执行单轮任务。 */
public class RunController {
    private final RunExecutionService executionService;
    private final RunPersistenceService persistenceService;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;
    /**
     * RunController：执行当前流程并返回处理结果。
     */
    public RunController(
            RunExecutionService executionService,
            RunPersistenceService persistenceService,
            PermissionEvaluator permissionEvaluator,
            AuditAppender auditAppender
    ) {
        this.executionService = executionService;
        this.persistenceService = persistenceService;
        this.permissionEvaluator = permissionEvaluator;
        this.auditAppender = auditAppender;
    }

    /**
     * 启动指定 Agent 的单轮运行。
     *
     * @param agentId        Agent 标识
     * @param request        运行输入
     * @param authentication 当前请求认证信息
     * @return Agent 运行结果
     * @throws ResponseStatusException 未认证、无权限或 Agent 不可运行时抛出
     */
    @PostMapping
    public AgentRunResult run(
            @PathVariable("agentId") UUID agentId,
            @Valid @RequestBody RunRequest request,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:run", "AGENT", agentId.toString());
        return executionService.run(principal, agentId, request.input());
    }

    /**
     * 按游标分页查询指定 Agent 的运行记录。
     *
     * @param agentId        Agent 标识
     * @param limit          单页最大记录数
     * @param cursor         上一页返回的游标，可为空
     * @param authentication 当前请求认证信息
     * @return 当前页记录及下一页游标
     * @throws ResponseStatusException 未认证、无权限或游标格式错误时抛出
     */
    @GetMapping
    public RunPageResponse list(
            @PathVariable("agentId") UUID agentId,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", "AGENT", agentId.toString());
        CursorPosition position = decodeCursor(cursor);
        RunPageRequest pageRequest = new RunPageRequest(
                limit,
                position == null ? null : position.startedAt(),
                position == null ? null : position.id()
        );
        List<RunRecord> items = persistenceService.list(principal.tenantId(), agentId, pageRequest);
        String nextCursor = null;
        if (items.size() == limit && !items.isEmpty()) {
            RunRecord last = items.getLast();
            boolean hasNext = !persistenceService.list(
                    principal.tenantId(), agentId, new RunPageRequest(1, last.startedAt(), last.id())
            ).isEmpty();
            if (hasNext) {
                nextCursor = encodeCursor(last);
            }
        }
        return new RunPageResponse(items, nextCursor);
    }

    /**
     * 查询指定 Agent 的单次运行详情。
     *
     * @param agentId        Agent 标识
     * @param runId          运行标识
     * @param authentication 当前请求认证信息
     * @return 运行记录及工具调用详情
     * @throws ResponseStatusException 未认证、无权限或运行不存在时抛出
     */
    @GetMapping("/{runId}")
    public RunDetailResponse get(
            @PathVariable("agentId") UUID agentId,
            @PathVariable("runId") UUID runId,
            Authentication authentication
    ) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", "AGENT", agentId.toString());
        RunPersistenceService.RunDetail detail = persistenceService.findDetail(
                principal.tenantId(), agentId, runId
        );
        return new RunDetailResponse(detail.run(), detail.toolCalls());
    }

    /**
     * decodeCursor：读取并解析输入内容。
     */
    private CursorPosition decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            if (cursor.isBlank()) {
                throw new IllegalArgumentException("空游标");
            }
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] fields = decoded.split("\\|", -1);
            if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IllegalArgumentException("游标格式不合法");
            }
            return new CursorPosition(Instant.parse(fields[0]), UUID.fromString(fields[1]));
        } catch (RuntimeException ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求参数不合法");
        }
    }

    /**
     * encodeCursor：转换并生成规范化输出。
     */
    private String encodeCursor(RunRecord run) {
        String value = run.startedAt() + "|" + run.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * principal：处理该类内部的业务逻辑或辅助计算。
     */
    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(), Set.copyOf(session.permissions()));
    }

    /**
     * authorize：处理该类内部的业务逻辑或辅助计算。
     */
    private void authorize(PrincipalRef principal, String permission, String resourceType, String resourceId) {
        AuthorizationDecision decision = permissionEvaluator.check(principal, permission);
        if (!decision.allowed()) {
            auditAppender.accessDenied(principal, resourceType, resourceId, permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    /**
     * RunRequest：不可变数据载体，用于在本模块内传递结构化信息。
     */
    public record RunRequest(@NotBlank String input) {
    }

    /**
     * RunPageResponse：不可变数据载体，用于在本模块内传递结构化信息。
     */
    public record RunPageResponse(List<RunRecord> items, String nextCursor) {
        public RunPageResponse {
            items = List.copyOf(items);
        }
    }

    /**
     * RunDetailResponse：不可变数据载体，用于在本模块内传递结构化信息。
     */
    public record RunDetailResponse(RunRecord run, List<RunToolCall> toolCalls) {
        public RunDetailResponse {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    /**
     * CursorPosition：不可变数据载体，用于在本模块内传递结构化信息。
     */
    private record CursorPosition(Instant startedAt, UUID id) {
    }
}
