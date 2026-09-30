package com.cmagent.server.runtime;

import com.cmagent.core.domain.ApprovalExpiryPage;
import com.cmagent.core.domain.ToolApprovalRequest;
import com.cmagent.core.repository.ToolApprovalRepository;
import com.cmagent.server.config.ApprovalExpiryProperties;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger.DiagnosticContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 有界发现并逐项收口过期审批；只在此系统边界允许跨租户查询。
 * 不执行工具、不恢复 Runtime，不接收用户身份或旧权限。
 */
@Component
public class ApprovalExpiryScanner {
    /** 仓储候选发现入口，仅供系统任务使用。 */
    private final ToolApprovalRepository repository;
    /** 统一过期事务编排入口。 */
    private final ToolApprovalService approvals;
    /** 服务端受控扫描配置。 */
    private final ApprovalExpiryProperties properties;
    /** 最终异步失败边界的脱敏诊断器。 */
    private final ErrorDiagnosticLogger diagnostics;
    /** 服务端时间来源，测试可替换为固定时钟。 */
    private final Clock clock;
    /** 当前遍历的到期时间游标；重启归零并重读数据库，非领取或执行租约。 */
    private Instant afterTime;
    /** 当前遍历的审批标识游标；失败项也前移，避免单项故障阻塞其他租户。 */
    private UUID afterId;

    /** Spring 装配时校验扫描配置并固定使用服务端 UTC 时钟；非法配置使启动失败。 */
    @Autowired
    public ApprovalExpiryScanner(ToolApprovalRepository repository, ToolApprovalService approvals,
            ApprovalExpiryProperties properties, ErrorDiagnosticLogger diagnostics) {
        this(repository, approvals, properties, diagnostics, Clock.systemUTC());
    }

    ApprovalExpiryScanner(ToolApprovalRepository repository, ToolApprovalService approvals,
            ApprovalExpiryProperties properties, ErrorDiagnosticLogger diagnostics, Clock clock) {
        properties.validate();
        this.repository = repository;
        this.approvals = approvals;
        this.properties = properties;
        this.diagnostics = diagnostics;
        this.clock = clock;
    }

    /**
     * 一轮最多读取一批；10 秒后不再开始新项，正在执行的单项 JDBC 事务另有 10 秒超时。
     * fixedDelay 从本轮结束计时，同实例串行；多实例仅以数据库条件更新竞争。
     */
    @Scheduled(fixedDelayString = "${cm-agent.approval-expiry.interval:30s}")
    public synchronized void scan() {
        if (!properties.isEnabled()) return;
        Instant cutoff = clock.instant();
        long started = System.nanoTime();
        java.util.List<ToolApprovalRequest> candidates;
        try {
            candidates = repository.findExpiredPending(new ApprovalExpiryPage(cutoff, afterTime, afterId,
                    properties.getBatchSize()));
        } catch (RuntimeException failure) {
            diagnose(null, failure);
            return;
        }
        if (candidates.isEmpty()) {
            afterTime = null;
            afterId = null;
            return;
        }
        for (ToolApprovalRequest candidate : candidates) {
            if (System.nanoTime() - started >= java.time.Duration.ofSeconds(10).toNanos()) break;
            try {
                approvals.expireFromSystem(candidate, cutoff);
            } catch (RuntimeException failure) {
                // JDBC 已回滚当前项；推进游标让其他项继续，遍历到底后从头重试失败项。
                diagnose(candidate, failure);
            }
            afterTime = candidate.expiresAt();
            afterId = candidate.id();
        }
    }

    private void diagnose(ToolApprovalRequest candidate, RuntimeException failure) {
        diagnostics.error(new DiagnosticContext(UUID.randomUUID().toString(), "APPROVAL_EXPIRY",
                "APPROVAL_EXPIRY_FAILED", candidate == null ? null : candidate.tenantId().toString(),
                "system:approval-expiry", candidate == null ? null : candidate.agentId().toString(),
                candidate == null ? null : candidate.runId().toString(), null, null,
                candidate == null ? "operation=discover" : "operation=expire,approvalId=" + candidate.id()), failure);
    }
}
