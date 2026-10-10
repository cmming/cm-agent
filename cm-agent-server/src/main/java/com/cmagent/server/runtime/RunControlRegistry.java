package com.cmagent.server.runtime;

import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.runtime.RunCancellation;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 单实例的运行控制句柄和短临界区，由 RunExecutionService 持有并共享给会话及审批编排。
 * 分段锁数量固定，历史 Run 不会留下无界锁对象；锁不覆盖模型或外部工具执行。
 */
final class RunControlRegistry {
    /** 只保存仍在执行或收口的句柄，完整会话编排结束后删除。 */
    private final ConcurrentHashMap<UUID, Execution> executions = new ConcurrentHashMap<>();
    /** 停止、终态、审批接受及过期共用的运行短锁。 */
    private final ReentrantLock[] runLocks = locks();
    /** 同会话创建新 Run 的短锁，与运行锁分开，避免锁次序循环。 */
    private final ReentrantLock[] conversationLocks = locks();

    Execution register(RunRecord run, UUID conversationId) {
        Execution execution = new Execution(run, conversationId);
        if (executions.putIfAbsent(run.id(), execution) != null) {
            throw new IllegalStateException("本轮运行已有执行句柄");
        }
        return execution;
    }

    Execution find(UUID runId) {
        return executions.get(runId);
    }

    void release(UUID runId) {
        executions.remove(runId);
    }

    /** 上一片段的 finally 只能释放自身句柄，不能删除同 Run 审批后新登记的执行。 */
    void release(UUID runId, Execution expected) {
        executions.remove(runId, expected);
    }

    <T> T withRun(UUID id, Supplier<T> operation) {
        return locked(runLocks, id, operation);
    }

    <T> T withConversation(UUID id, Supplier<T> operation) {
        return locked(conversationLocks, id, operation);
    }

    private static ReentrantLock[] locks() {
        ReentrantLock[] result = new ReentrantLock[64];
        for (int index = 0; index < result.length; index++) result[index] = new ReentrantLock();
        return result;
    }

    private static <T> T locked(ReentrantLock[] locks, UUID id, Supplier<T> operation) {
        ReentrantLock lock = locks[id.hashCode() & (locks.length - 1)];
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    /** 保存可信运行归属、共享信号与经过服务端脱敏的部分正文，跨事件线程访问须同步。 */
    static final class Execution {
        /** 捕获正文的固定字符上限，不持有无界模型输出。 */
        private static final int MAX_OUTPUT = 60_000;
        /** 登记时从仓储得到的运行，不接受客户端字段覆盖。 */
        private final RunRecord run;
        /** 本次执行归属的持久化会话。 */
        private final UUID conversationId;
        /** adapter 与治理调用共享的协作停止信号。 */
        private final RunCancellation cancellation = new RunCancellation();
        /** 只累积脱敏正文，不包含 thinking 或原始工具参数。 */
        private final StringBuilder output = new StringBuilder();

        private Execution(RunRecord run, UUID conversationId) {
            this.run = run;
            this.conversationId = conversationId;
        }

        RunCancellation cancellation() { return cancellation; }

        boolean belongsTo(RunRecord expected, UUID conversation) {
            return run.tenantId().equals(expected.tenantId()) && run.principalId().equals(expected.principalId())
                    && run.agentId().equals(expected.agentId()) && conversationId.equals(conversation);
        }

        synchronized void append(String delta) {
            if (!cancellation.isRequested() && delta != null && output.length() < MAX_OUTPUT) {
                output.append(delta, 0, Math.min(delta.length(), MAX_OUTPUT - output.length()));
            }
        }

        synchronized String output() { return output.toString(); }
    }
}
