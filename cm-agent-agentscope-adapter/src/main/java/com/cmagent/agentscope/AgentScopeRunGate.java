package com.cmagent.agentscope;

import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationInfrastructureException;
import com.cmagent.core.runtime.ToolInvocationRequest;
import com.cmagent.core.runtime.ToolInvocationResult;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

final class AgentScopeRunGate {

    private final ReentrantLock invocationLock = new ReentrantLock(true);
    private final AtomicReference<ToolInvocationInfrastructureException> infrastructureFailure =
            new AtomicReference<>();
    private final AtomicBoolean toolTimedOut = new AtomicBoolean();
    private final AtomicBoolean invocationInterrupted = new AtomicBoolean();
    private final AtomicBoolean interrupted = new AtomicBoolean();
    private final AtomicReference<RuntimeException> interruptFailure = new AtomicReference<>();
    private final ConcurrentHashMap<String, StringBuffer> toolResultTexts = new ConcurrentHashMap<>();
    private final String toolTimeoutResult;

    AgentScopeRunGate() {
        this.toolTimeoutResult = "";
    }

    AgentScopeRunGate(Duration toolTimeout) {
        this.toolTimeoutResult = "Error: Tool execution failed: Tool execution timeout after " + toolTimeout;
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
            throwIfInvocationInterrupted();
            try {
                ToolInvocationResult result = gateway.invoke(request);
                throwIfInfrastructureFailure();
                throwIfToolTimedOut();
                throwIfInvocationInterrupted();
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

    void observeToolResultText(String toolCallId, String text) {
        if (toolCallId != null && text != null) {
            toolResultTexts.computeIfAbsent(toolCallId, ignored -> new StringBuffer()).append(text);
        }
    }

    void observeToolResultEnd(String toolCallId, boolean bridgeCompleted) {
        StringBuffer text = toolCallId == null ? null : toolResultTexts.remove(toolCallId);
        // AgentScope 2.0.0 把工具错误终态也标成 SUCCESS，只接受未由 Bridge 完成的精确超时包装。
        if (!bridgeCompleted && text != null && toolTimeoutResult.contentEquals(text)) {
            markToolTimeout();
        }
    }

    boolean isToolTimedOut() {
        return toolTimedOut.get();
    }

    void markInvocationInterrupted() {
        invocationInterrupted.set(true);
    }

    private void throwIfToolTimedOut() {
        if (toolTimedOut.get()) {
            throw new RunAbortedException();
        }
    }

    private void throwIfInvocationInterrupted() {
        if (invocationInterrupted.get()) {
            throw new RunAbortedException();
        }
    }

    void interruptOnce(Runnable interruptAction) {
        if (interrupted.compareAndSet(false, true)) {
            try {
                interruptAction.run();
            } catch (RuntimeException failure) {
                interruptFailure.compareAndSet(null, failure);
                throw failure;
            }
        }
    }

    RuntimeException interruptFailure() {
        return interruptFailure.get();
    }

    static final class RunAbortedException extends RuntimeException {

        RunAbortedException() {
        }

        RunAbortedException(InterruptedException cause) {
            super(cause);
        }
    }
}
