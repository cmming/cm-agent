package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.runtime.WorkspaceContextException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class DeferredWorkspaceFailureTest {
    @Test void 审计只在外层事务退出后完成一次(){
        var calls=new AtomicInteger();var deferred=new DeferredWorkspaceFailure(()->{calls.incrementAndGet();return new WorkspaceContextException(ApiErrorCode.WORKSPACE_IMPORT_CONFLICT,"合成受控失败","test-correlation");});
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(deferred::finish).isInstanceOf(IllegalStateException.class);assertThat(calls.get()).isZero();}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        var completed=deferred.finish();assertThat(deferred.finish()).isSameAs(completed);assertThat(calls.get()).isEqualTo(1);
    }
}
