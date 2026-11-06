package com.cmagent.server.runtime;

import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.server.audit.AuditAppender;
import java.time.Clock;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 持有当前节点独占锁后的启动恢复，只收口失联元数据，不触碰项目根、密文正文、原进程或模型。
 * 宿主必须在接受新请求前同步调用recover；任何基础设施或严格审计失败都会阻止启动。
 */
public final class LocalProjectRecoveryService {
    /** 失败日志省略动态消息和原因链，避免启动异常回显SQL/路径/正文。 */
    private static final Logger log = LoggerFactory.getLogger(LocalProjectRecoveryService.class);
    /** 每页完成后活动态被移除，再读首批，直到当前节点排空。 */
    private static final int BATCH = 100;
    /** 节点只来自部署配置，不提供用户驱动的跨租户发现入口。 */ private final String nodeId;
    /** 外部宿主持有并关闭，恢复阶段只核验有效性。 */ private final LocalProjectStateLock state;
    /** 服务端收口时间。 */ private final Clock clock;
    /** 完整来源和当前节点发现。 */ private final LocalProjectBindingRepository bindings;
    /** 只将已派发RUNNING转为UNKNOWN。 */ private final LocalProjectOperationRepository operations;
    /** 精确归属/状态/开始时间CAS，不使用普通停止的CANCELLED语义。 */ private final RunRepository runs;
    /** 与UNKNOWN推进及Run收口共享事务的元数据审计。 */ private final AuditAppender audit;
    /** 与三个仓储及审计共享数据源，不含任何本机副作用。 */ private final TransactionTemplate transaction;

    /**
     * @param nodeId 固定受信节点
     * @param state 已成功取得的私有状态卷独占锁，本服务不释放
     * @param clock 服务端时钟
     * @param bindings 来源4的内部发现仓储
     * @param operations 已派发操作的条件推进仓储
     * @param runs 运行精确终态CAS仓储
     * @param audit 严格审计，失败必须回滚
     * @param transaction 与仓储/审计同数据源的REQUIRED短事务
     */
    LocalProjectRecoveryService(String nodeId, LocalProjectStateLock state, Clock clock, LocalProjectBindingRepository bindings,
            LocalProjectOperationRepository operations, RunRepository runs, AuditAppender audit, TransactionTemplate transaction) {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("恢复节点不能为空");
        this.nodeId = nodeId; this.state = Objects.requireNonNull(state); this.clock = Objects.requireNonNull(clock);
        this.bindings = Objects.requireNonNull(bindings); this.operations = Objects.requireNonNull(operations);
        this.runs = Objects.requireNonNull(runs); this.audit = Objects.requireNonNull(audit); this.transaction = Objects.requireNonNull(transaction);
    }

    /**
     * 同步排空本节点失联RUNNING/CANCELLING；WAITING_APPROVAL及其检查点原样保留。
     * @return 实际收口的运行数；重复调用在无新失联运行时为零
     * @throws RecoveryFailure 发现、CAS、锁或审计失败；诊断已记录同号，不能继续接受项目请求
     */
    public synchronized int recover() {
        int completed = 0; Set<String> seen = new HashSet<>();
        while (true) {
            List<LocalProjectBinding> batch;
            try { state.requireHeld(); batch = bindings.findInterruptedRuns(nodeId, BATCH); }
            catch (RuntimeException failure) { throw diagnose(null, failure); }
            if (batch.isEmpty()) return completed;
            for (var binding : batch) {
                try {
                    if (!nodeId.equals(binding.nodeId()) || binding.runId() == null
                            || !seen.add(binding.tenantId() + ":" + binding.runId())) throw new IllegalStateException("失联运行发现没有推进");
                    state.requireHeld();
                    if (Boolean.TRUE.equals(transaction.execute(status -> recoverRun(binding)))) completed++;
                } catch (RuntimeException failure) { throw diagnose(binding, failure); }
            }
        }
    }

    private boolean recoverRun(LocalProjectBinding binding) {
        var run = runs.findByTenantAndAgentAndId(binding.tenantId(), binding.agentId(), binding.runId()).orElseThrow();
        if (!run.principalId().equals(binding.ownerId()) || run.kind() != RunKind.NORMAL
                || runs.workspaceProtocol(binding.tenantId(), binding.agentId(), binding.runId()) != LocalProjectBinding.PROTOCOL)
            throw new IllegalStateException("失联运行来源不一致");
        if (run.status() != RunStatus.RUNNING && run.status() != RunStatus.CANCELLING) return false;
        var now = clock.instant();
        Set<UUID> seenOperations = new HashSet<>();
        while (true) {
            var dispatched = operations.findRunningForRun(binding, BATCH);
            if (dispatched.isEmpty()) break;
            for (var operation : dispatched) {
                if (!sameRun(binding, operation) || operation.state() != LocalProjectOperation.State.RUNNING || !seenOperations.add(operation.id()))
                    throw new IllegalStateException("已派发操作来源不一致");
                var advanced = operations.advance(binding.tenantId(), binding.ownerId(), operation.id(), operation.version(), LocalProjectOperation.State.UNKNOWN, now).orElseThrow();
                if (advanced.state() != LocalProjectOperation.State.UNKNOWN || advanced.version() <= operation.version())
                    throw new IllegalStateException("失联操作条件推进未生效");
                audit.appendControlled(binding.tenantId(), binding.ownerId(), "PROJECT_COLD_START_UNKNOWN", "PROJECT_OPERATION", operation.id().toString(),
                        "UNKNOWN", "runId=" + binding.runId() + " kind=" + operation.kind() + " version=" + advanced.version());
            }
        }
        // CAS失败必须抛出，使同一事务中的所有UNKNOWN推进和审计一起回滚，不能部分收口。
        if (!runs.failInterrupted(run, now)) throw new IllegalStateException("失联运行终态竞争失败");
        audit.appendControlled(binding.tenantId(), binding.ownerId(), "PROJECT_COLD_START_RECOVERY", "RUN", run.id().toString(), "FAILED", "原执行结果未知，仅收口运行元数据");
        return true;
    }

    private static boolean sameRun(LocalProjectBinding binding, LocalProjectOperation operation) {
        return operation.tenantId().equals(binding.tenantId()) && operation.ownerId().equals(binding.ownerId())
                && operation.projectId().equals(binding.projectId()) && operation.agentId().equals(binding.agentId())
                && operation.conversationId().equals(binding.conversationId()) && operation.runId().equals(binding.runId());
    }

    private RecoveryFailure diagnose(LocalProjectBinding binding, RuntimeException failure) {
        String id = UUID.randomUUID().toString(); var safe = new IllegalStateException("冷启动恢复失败，动态异常消息已省略"); safe.setStackTrace(failure.getStackTrace());
        log.error("errorId={} operation=LOCAL_PROJECT_RECOVERY errorCode=PROJECT_RECOVERY_FAILED tenantId={} principalId={} runId={} resourceType=RUN resourceId={} exceptionType={}",
                id, binding == null ? "-" : binding.tenantId(), binding == null ? "system:local-project-recovery" : binding.ownerId(),
                binding == null ? "-" : binding.runId(), binding == null ? "-" : binding.runId(), failure.getClass().getName(), safe);
        return new RecoveryFailure(id);
    }

    /** 安全启动失败不带原始cause，宿主日志仅显示同号固定说明。 */
    public static final class RecoveryFailure extends IllegalStateException {
        /** 与最终安全诊断一致的关联号。 */ private final String errorId;
        private RecoveryFailure(String errorId) { super("本地项目冷启动恢复失败 errorCode=PROJECT_RECOVERY_FAILED errorId=" + errorId); this.errorId = errorId; }
        /** @return 后台可检索的同号诊断编号 */ public String errorId() { return errorId; }
    }
}
