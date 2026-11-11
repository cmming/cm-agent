package com.cmagent.persistence;

import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.RunRecord;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 只在首次 Run 插入的同一 JDBC 事务内传递不可重放的进程内创建见证。 */
final class ClientProjectRunCreationWitness {
    private static final Object KEY = new Object();
    private ClientProjectRunCreationWitness() { }

    /** 新 Run 插入后登记；单独提交的旧 Run 绝不能在后续事务补造首代归属。 */
    static void inserted(RunRecord run) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        State state = (State) TransactionSynchronizationManager.getResource(KEY);
        if (state == null) {
            state = new State();
            State owned = state;
            TransactionSynchronizationManager.bindResource(KEY, owned);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void suspend() { TransactionSynchronizationManager.unbindResourceIfPossible(KEY); }
                @Override public void resume() { TransactionSynchronizationManager.bindResource(KEY, owned); }
                @Override public void afterCompletion(int status) {
                    if (TransactionSynchronizationManager.getResource(KEY) == owned)
                        TransactionSynchronizationManager.unbindResource(KEY);
                }
            });
        }
        state.runs.add(new Identity(run.tenantId(), run.id(), run.principalId()));
    }

    /** 见证只供同一事务消费一次；还须由仓储核对不可变绑定和数据库行锁。 */
    static boolean consume(ClientProjectBinding binding) {
        State state = (State) TransactionSynchronizationManager.getResource(KEY);
        return state != null && state.runs.remove(new Identity(binding.tenantId(), binding.runId(), binding.ownerId()));
    }

    private static final class State { private final Set<Identity> runs = new HashSet<>(); }

    /** @param tenant 可信租户 @param run 刚插入的 Run @param owner 原认证主体 */
    private record Identity(UUID tenant, UUID run, String owner) { }
}
