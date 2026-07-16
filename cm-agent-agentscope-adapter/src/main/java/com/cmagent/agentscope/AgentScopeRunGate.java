package com.cmagent.agentscope;

import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationInfrastructureException;
import com.cmagent.core.runtime.ToolInvocationRequest;
import com.cmagent.core.runtime.ToolInvocationResult;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

final class AgentScopeRunGate {

    private final ReentrantLock invocationLock = new ReentrantLock(true);
    private final AtomicReference<ToolInvocationInfrastructureException> infrastructureFailure =
            new AtomicReference<>();
    private final AtomicBoolean toolTimedOut = new AtomicBoolean();
    private final AtomicBoolean interrupted = new AtomicBoolean();
    private final long toolTimeoutNanos;

    AgentScopeRunGate() {
        this.toolTimeoutNanos = 0;
    }

    AgentScopeRunGate(Duration toolTimeout) {
        this.toolTimeoutNanos = toolTimeout.toNanos();
    }

    ToolInvocationResult invoke(ToolInvocationGateway gateway, ToolInvocationRequest request) {
        try {
            invocationLock.lockInterruptibly();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new RunAbortedException(interruptedException);
        }
        try {
            throwIfInfrastructureFailure();
            throwIfToolTimedOut();
            try {
                ToolInvocationResult result = gateway.invoke(request);
                throwIfInfrastructureFailure();
                throwIfToolTimedOut();
                return result;
            } catch (ToolInvocationInfrastructureException failure) {
                infrastructureFailure.compareAndSet(null, failure);
                throw infrastructureFailure.get();
            }
        } finally {
            invocationLock.unlock();
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

    void markToolTimeoutIfElapsed(long startedAtNanos) {
        // AgentScope 2.0.0 未暴露取消原因；仅达到已配置工具时限的取消可判定为超时。
        if (toolTimeoutNanos > 0 && System.nanoTime() - startedAtNanos >= toolTimeoutNanos) {
            markToolTimeout();
        }
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

        RunAbortedException() {
        }

        RunAbortedException(InterruptedException cause) {
            super(cause);
        }
    }
}
