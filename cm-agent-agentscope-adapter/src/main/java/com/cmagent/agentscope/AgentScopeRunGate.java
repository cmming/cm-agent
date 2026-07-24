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

/**
 * 协调工具调用串行化、超时、中断和基础设施失败状态。
 */
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

    /**
     * 创建不带特定超时错误文本的运行门控。
     */
    AgentScopeRunGate() {
        this.toolTimeoutResult = "";
    }

    /**
     * 创建带工具超时错误文本的运行门控。
     */
    AgentScopeRunGate(Duration toolTimeout) {
        this.toolTimeoutResult = "Error: Tool execution failed: Tool execution timeout after " + toolTimeout;
    }

    /**
     * 串行执行一次工具调用，并在调用前后检查运行中止状态。
     */
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

    /**
     * 若已记录工具基础设施失败，则重新抛出该失败。
     */
    void throwIfInfrastructureFailure() {
        ToolInvocationInfrastructureException failure = infrastructureFailure.get();
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 标记工具调用已经超时。
     */
    void markToolTimeout() {
        toolTimedOut.set(true);
    }

    /**
     * 累积 AgentScope 工具结果文本片段，用于识别超时结果。
     */
    void observeToolResultText(String toolCallId, String text) {
        if (toolCallId != null && text != null) {
            toolResultTexts.computeIfAbsent(toolCallId, ignored -> new StringBuffer()).append(text);
        }
    }

    /**
     * 处理工具结果终态事件，并识别未被桥接器完成的超时结果。
     */
    void observeToolResultEnd(String toolCallId, boolean bridgeCompleted) {
        StringBuffer text = toolCallId == null ? null : toolResultTexts.remove(toolCallId);
        // AgentScope 2.0.0 把工具错误终态也标成 SUCCESS，只接受未由 Bridge 完成的精确超时包装。
        if (!bridgeCompleted && text != null && toolTimeoutResult.contentEquals(text)) {
            markToolTimeout();
        }
    }

    /**
     * 返回是否已发生工具超时。
     */
    boolean isToolTimedOut() {
        return toolTimedOut.get();
    }

    /**
     * 标记当前工具调用已被取消或中断。
     */
    void markInvocationInterrupted() {
        invocationInterrupted.set(true);
    }

    /**
     * 返回当前工具调用是否已被中断。
     */
    boolean isInvocationInterrupted() {
        return invocationInterrupted.get();
    }

    /**
     * 在工具超时后阻止新的工具调用继续执行。
     */
    private void throwIfToolTimedOut() {
        if (toolTimedOut.get()) {
            throw new RunAbortedException();
        }
    }

    /**
     * 在工具调用被中断后阻止其继续执行。
     */
    private void throwIfInvocationInterrupted() {
        if (invocationInterrupted.get()) {
            throw new RunAbortedException();
        }
    }

    /**
     * 保证中断动作最多执行一次，并记录首次中断失败。
     */
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

    /**
     * 返回已记录的中断失败，否则返回 {@code null}。
     */
    RuntimeException interruptFailure() {
        return interruptFailure.get();
    }

    /**
     * 表示工具调用因超时、取消或线程中断而终止。
     */
    static final class RunAbortedException extends RuntimeException {

        /**
         * 创建不带原因的中止异常。
         */
        RunAbortedException() {
        }

        /**
         * 创建带线程中断原因的中止异常。
         */
        RunAbortedException(InterruptedException cause) {
            super(cause);
        }
    }
}
