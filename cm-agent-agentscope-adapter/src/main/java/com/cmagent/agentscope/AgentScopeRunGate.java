package com.cmagent.agentscope;

import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationInfrastructureException;
import com.cmagent.core.runtime.ToolInvocationRequest;
import com.cmagent.core.runtime.ToolInvocationResult;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class AgentScopeRunGate {

    private final Object invocationLock = new Object();
    private final AtomicReference<ToolInvocationInfrastructureException> infrastructureFailure =
            new AtomicReference<>();
    private final AtomicBoolean toolTimedOut = new AtomicBoolean();
    private final AtomicBoolean interrupted = new AtomicBoolean();

    ToolInvocationResult invoke(ToolInvocationGateway gateway, ToolInvocationRequest request) {
        synchronized (invocationLock) {
            throwIfInfrastructureFailure();
            throwIfToolTimedOut();
            try {
                return gateway.invoke(request);
            } catch (ToolInvocationInfrastructureException failure) {
                infrastructureFailure.compareAndSet(null, failure);
                throw infrastructureFailure.get();
            }
        }
    }

    void throwIfInfrastructureFailure() {
        ToolInvocationInfrastructureException failure = infrastructureFailure.get();
        if (failure != null) {
            throw failure;
        }
    }

    void markToolTimeout() {
        toolTimedOut.set(true);
    }

    boolean isToolTimedOut() {
        return toolTimedOut.get();
    }

    private void throwIfToolTimedOut() {
        if (toolTimedOut.get()) {
            throw new RunAbortedException();
        }
    }

    void interruptOnce(Runnable interruptAction) {
        if (interrupted.compareAndSet(false, true)) {
            interruptAction.run();
        }
    }

    static final class RunAbortedException extends RuntimeException {
    }
}
