package com.cmagent.persistence;

import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.domain.ClientProjectRunOwnership;
import com.cmagent.core.repository.ClientProjectRunOwnershipRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用同一 JDBC 事务隔离旧任务并写入严格审计的恢复屏障。
 *
 * <p>必须仅由可信服务端在归属仓储接管回调内使用；本组件不领取设备任务、确认客户端代次
 * 或停止外部进程。审计主体固定为系统恢复者，Run 原主体仍可通过可信来源记录追溯；
 * 审计写入失败必须向归属事务传播，禁止吞掉异常或先提交隔离。</p>
 */
public final class JdbcClientProjectRecoveryBarrier
        implements ClientProjectRunOwnershipRepository.RecoveryBarrier {
    private final JdbcClientProjectRunIsolation isolation;
    private final JdbcAuditEventRepository audit;

    /** @param ownership 为隔离和审计提供同一 JDBC 数据源的归属仓储 */
    public JdbcClientProjectRecoveryBarrier(JdbcClientProjectRunOwnershipRepository ownership) {
        Objects.requireNonNull(ownership);
        isolation = new JdbcClientProjectRunIsolation(ownership);
        audit = new JdbcAuditEventRepository(ownership.jdbc(), ownership.transaction());
    }

    /** 将旧任务隔离和不含载荷的审计事件写入调用方持有的恢复事务。 */
    @Override public void quarantineAndAudit(ClientProjectRunOwnership previous,
            ClientProjectRunOwnership next) {
        var counts = isolation.quarantine(previous, next);
        audit.append(new AuditEvent(UUID.randomUUID(), next.tenantId(), "system:client-project-recovery",
                "CLIENT_PROJECT_RUN_RECOVERY", "CLIENT_PROJECT_RUN", next.runId().toString(),
                "RECOVERING", "epoch=" + next.epoch() + " commands=" + counts.commands()
                        + " operations=" + counts.operations() + " tasks=" + counts.tasks(), Instant.now()));
    }
}
