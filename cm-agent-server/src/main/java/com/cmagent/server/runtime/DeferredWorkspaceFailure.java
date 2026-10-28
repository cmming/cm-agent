package com.cmagent.server.runtime;

import com.cmagent.core.runtime.WorkspaceContextException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 让拒绝审计在业务短事务回滚后完成，避免审计随业务回滚消失，或独立事务等待外层仍持有的租户锁。
 * 不把原始异常作为cause暴露；最终HTTP/SSE边界必须调用finish，不能把此对象当作已诊断失败。
 */
public final class DeferredWorkspaceFailure extends RuntimeException {
    /** 仅捕获服务端可信上下文的最终边界，不包含要输出到日志的原始请求。 */
    private final Supplier<WorkspaceContextException> finalizer;
    /** 同一个失败在跨线程边界最多完成一次严格审计及诊断。 */
    private WorkspaceContextException finished;
    /** @param finalizer 外层短事务退出后执行的可信审计/诊断回调 */
    DeferredWorkspaceFailure(Supplier<WorkspaceContextException> finalizer){
        super("工作区失败需在业务事务退出后完成诊断",null,false,false);this.finalizer=Objects.requireNonNull(finalizer);
    }
    /**
     * @return 已记录严格审计与同号诊断的受控异常；审计不可用会转换为明确503而非吞掉
     * @throws IllegalStateException 仍处于业务事务中时拒绝执行，防止独立审计自锁或再次被回滚
     */
    public synchronized WorkspaceContextException finish(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("必须在工作区业务短事务退出后完成失败诊断");
        if(finished==null)finished=finalizer.get();return finished;
    }
}
