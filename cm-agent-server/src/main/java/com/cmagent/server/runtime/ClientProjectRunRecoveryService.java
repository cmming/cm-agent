package com.cmagent.server.runtime;

import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.ClientProjectRunOwnership;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.repository.ClientProjectBindingRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.persistence.JdbcClientProjectRunOwnershipRepository;
import com.cmagent.persistence.JdbcClientProjectRunRecoveryRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.ClientProjectProperties;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 设备项目 Run 的租约心跳、过期接管和已确认恢复收口。
 *
 * <p>过期只允许进入 RECOVERING，旧任务在同一事务隔离为 CANCELLED/UNKNOWN。只有设备对相同
 * 实例和代次完成签名确认后，Run 才能收口为 FAILED；这不证明设备进程已停止，命令与文件变更的
 * UNKNOWN 状态及项目根隔离继续保留。普通 Run 终态则在同一持久事务关闭当前归属。</p>
 */
public final class ClientProjectRunRecoveryService {
    private static final int BATCH = 100;
    private static final Logger log = LoggerFactory.getLogger(ClientProjectRunRecoveryService.class);
    private final JdbcClientProjectRunOwnershipRepository ownership;
    private final JdbcClientProjectRunRecoveryRepository recovery;
    private final ClientProjectBindingRepository bindings;
    private final RunRepository runs;
    private final ClientProjectRuntimeIdentity identity;
    private final TransactionTemplate transaction;
    private final AuditAppender audit;
    private final ErrorDiagnosticLogger diagnostics;
    private final Clock clock;
    private final java.time.Duration ownershipLease;

    /**
     * @param ownership 条件接管与心跳归属仓储
     * @param recovery 系统恢复扫描仓储
     * @param bindings 可信 Run 来源
     * @param runs Run 状态仓储
     * @param identity 当前 Server 随机实例
     * @param transaction 全部归属、Run 和审计共享的短事务
     * @param audit 严格恢复审计
     * @param diagnostics 安全失败诊断
     * @param clock 服务端时钟
     * @param properties 已验证的设备项目租期配置
     */
    public ClientProjectRunRecoveryService(JdbcClientProjectRunOwnershipRepository ownership,
            JdbcClientProjectRunRecoveryRepository recovery,
            ClientProjectBindingRepository bindings, RunRepository runs,
            ClientProjectRuntimeIdentity identity, TransactionTemplate transaction,
            AuditAppender audit, ErrorDiagnosticLogger diagnostics, Clock clock,
            ClientProjectProperties properties) {
        this.ownership = Objects.requireNonNull(ownership);
        this.recovery = Objects.requireNonNull(recovery);
        this.bindings = Objects.requireNonNull(bindings);
        this.runs = Objects.requireNonNull(runs);
        this.identity = Objects.requireNonNull(identity);
        this.transaction = Objects.requireNonNull(transaction);
        this.audit = Objects.requireNonNull(audit);
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.clock = Objects.requireNonNull(clock);
        this.ownershipLease = Objects.requireNonNull(properties).getOwnershipLease();
    }

    /**
     * 周期续期当前实例、推进过期恢复并收口设备已确认的失联 Run。
     */
    @Scheduled(fixedDelayString = "${cm-agent.client-projects.recovery-scan-interval:5s}")
    public synchronized void maintain() {
        String errorId = UUID.randomUUID().toString();
        try {
            recovery.renewInstance(identity.instanceId(), ownershipLease);
            recoverExpired();
            finalizeAcknowledged();
        } catch (RuntimeException failure) {
            SafeRecoveryException safe = new SafeRecoveryException("设备项目恢复巡检失败，异常内容已脱敏");
            safe.setStackTrace(failure.getStackTrace());
            diagnostics.error(new ErrorDiagnosticLogger.DiagnosticContext(errorId,
                    "CLIENT_PROJECT_RECOVERY_MAINTENANCE", "CLIENT_PROJECT_RECOVERY_UNAVAILABLE",
                    "-", "system:client-project-recovery", null, null, null, null,
                    "CLIENT_PROJECT"), safe, "CLIENT_PROJECT_RUN", "-");
            log.warn("errorId={} operation=CLIENT_PROJECT_RECOVERY_MAINTENANCE "
                    + "errorCode=CLIENT_PROJECT_RECOVERY_UNAVAILABLE exceptionType={}",
                    errorId, failure.getClass().getName());
        }
    }

    /**
     * 在 Run 状态写入的调用方事务内核对当前实例执行许可。
     *
     * @param run 可信仓储读取的 Run
     */
    public void requireCurrentOwnership(RunRecord run) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("设备项目Run收口必须使用短事务");
        }
        if (runs.workspaceProtocol(run.tenantId(), run.agentId(), run.id())
                != ClientProjectBinding.PROTOCOL) return;
        ownership.lockCurrentForInstance(run.tenantId(), run.principalId(), run.id(),
                        identity.instanceId())
                .orElseThrow(() -> new IllegalStateException("CLIENT_PROJECT_EXECUTION_FENCE_LOST"));
    }

    /**
     * 在正常终态事务内关闭当前实例归属。
     *
     * @param run 已按条件写进入终态的 Run
     */
    public void closeAfterTerminal(RunRecord run) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("设备项目Run终态收口必须使用短事务");
        }
        if (runs.workspaceProtocol(run.tenantId(), run.agentId(), run.id())
                != ClientProjectBinding.PROTOCOL) return;
        if (!ownership.closeCurrent(run.tenantId(), run.principalId(), run.id(), identity.instanceId())) {
            throw new IllegalStateException("CLIENT_PROJECT_EXECUTION_FENCE_LOST");
        }
    }

    private void recoverExpired() {
        var candidates = recovery.findExpired(BATCH);
        for (var candidate : candidates) {
            ownership.takeOverForRecovery(candidate.tenantId(), candidate.ownerId(), candidate.runId(),
                    candidate.epoch(), identity.instanceId(), ownershipLease);
        }
    }

    private void finalizeAcknowledged() {
        for (var candidate : recovery.findAcknowledgedRecovering(identity.instanceId(), BATCH)) {
            Boolean recovered = transaction.execute(status -> finalizeRun(candidate));
            if (Boolean.TRUE.equals(recovered)) {
                log.info("设备项目失联 Run 已按确认代次收口。runId={} epoch={}",
                        candidate.runId(), candidate.epoch());
            }
        }
    }

    private boolean finalizeRun(JdbcClientProjectRunRecoveryRepository.Candidate candidate) {
        var current = ownership.lockRecovery(candidate.tenantId(), candidate.ownerId(),
                candidate.runId(), identity.instanceId(), candidate.epoch()).orElse(null);
        if (current == null) return false;
        ClientProjectBinding binding = bindings.run(candidate.tenantId(), candidate.ownerId(),
                candidate.runId()).orElse(null);
        if (binding == null || binding.protocol() != ClientProjectBinding.PROTOCOL) {
            throw new IllegalStateException("恢复 Run 来源缺失");
        }
        RunRecord run = runs.findByTenantAndAgentAndId(candidate.tenantId(), binding.agentId(),
                candidate.runId()).orElse(null);
        if (run == null || !run.principalId().equals(candidate.ownerId()) || !run.status().isActive()) {
            return false;
        }
        Instant now = clock.instant();
        if (!runs.failClientProjectRecovered(run, now)) {
            throw new IllegalStateException("恢复 Run 终态竞争失败");
        }
        audit.appendControlled(candidate.tenantId(), "system:client-project-recovery",
                "CLIENT_PROJECT_RUN_RECOVERY_ACK", "CLIENT_PROJECT_RUN",
                candidate.runId().toString(), "FAILED", "epoch=" + candidate.epoch());
        return true;
    }

    /** 诊断包装不携带原始动态消息。 */
    private static final class SafeRecoveryException extends IllegalStateException {
        private SafeRecoveryException(String message) { super(message); }
    }
}
