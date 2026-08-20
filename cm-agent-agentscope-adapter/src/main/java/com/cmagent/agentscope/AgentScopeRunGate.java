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
 * 在同一次 AgentScope 运行内协调所有工具桥接器的并发与中止状态。
 *
 * <p>AgentScope 可能通过异步事件链调度多个工具调用，而 CM Agent 必须保证第一次基础设施失败、
 * 工具超时或取消发生后，不再有后续调用越过治理入口。为此，每次运行只创建一个门控并由所有
 * {@link AgentScopeToolBridge} 共享：</p>
 * <ul>
 *     <li>公平锁将网关调用串行化，等待锁的线程可响应中断；</li>
 *     <li>原子状态保存首次基础设施失败以及超时、取消信号；</li>
 *     <li>Agent 中断动作最多执行一次，并保留中断本身的失败。</li>
 * </ul>
 *
 * <p>门控采用协作式中止：无法强制撤销已经进入外部网关的副作用，但会在网关返回后再次检查状态，
 * 防止迟到结果被记录为成功。</p>
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
     * 创建不启用 AgentScope 超时文本识别的门控。
     *
     * <p>主要用于独立工具桥接器和单元测试；完整 ReAct 运行应提供配置的工具超时时间。</p>
     */
    AgentScopeRunGate() {
        this.toolTimeoutResult = "";
    }

    /**
     * 创建能够识别 AgentScope 2.0.0 工具超时包装文本的门控。
     *
     * @param toolTimeout 传给 AgentScope 工具执行配置的单次调用超时
     */
    AgentScopeRunGate(Duration toolTimeout) {
        this.toolTimeoutResult = "Error: Tool execution failed: Tool execution timeout after " + toolTimeout;
    }

    /**
     * 串行进入治理网关，并在调用前后检查运行是否仍可继续。
     *
     * <p>前置检查阻止已知失败后的新调用；后置检查丢弃超时或取消之后返回的迟到结果。
     * 若网关报告基础设施失败，只保留并重新抛出第一次失败，使同一运行中的并发调用观察到一致根因。</p>
     *
     * @param gateway 受治理的工具调用网关
     * @param request 携带可信运行上下文的工具调用请求
     * @return 网关返回的受控调用结果
     * @throws RunAbortedException 等待锁时线程被中断，或运行已超时、取消时抛出
     * @throws ToolInvocationInfrastructureException 网关基础设施失败时抛出首次记录的异常
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
     * 重新抛出本次运行记录的首次工具基础设施失败。
     *
     * <p>AgentScope 的响应式链可能消费工具 Publisher 的异常，执行器会在事件边界再次调用此方法，
     * 确保审计或持久化故障不会被降级为普通工具失败。</p>
     */
    void throwIfInfrastructureFailure() {
        ToolInvocationInfrastructureException failure = infrastructureFailure.get();
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 标记本次运行已观察到工具超时，供网关后置检查和 Agent 中断流程共同读取。
     */
    void markToolTimeout() {
        toolTimedOut.set(true);
    }

    /**
     * 按工具调用标识累积 AgentScope 工具结果增量，用于识别框架生成的超时包装。
     *
     * <p>不能仅凭单个增量判断，因为超时文本可能被拆成多个事件；不同工具调用也必须分别归集。</p>
     *
     * @param toolCallId AgentScope 工具调用标识
     * @param text 当前工具结果文本增量
     */
    void observeToolResultText(String toolCallId, String text) {
        if (toolCallId != null && text != null) {
            toolResultTexts.computeIfAbsent(toolCallId, ignored -> new StringBuffer()).append(text);
        }
    }

    /**
     * 在工具结果流结束时识别由 AgentScope 执行层生成的超时结果。
     *
     * <p>只有桥接器尚未完成、且聚合文本与当前配置生成的超时包装完全一致时才标记超时，
     * 避免把普通业务错误、授权拒绝或恰好包含“timeout”的模型文本误分类。</p>
     *
     * @param toolCallId AgentScope 工具调用标识
     * @param bridgeCompleted 桥接器是否已经产出该调用的受控终态记录
     */
    void observeToolResultEnd(String toolCallId, boolean bridgeCompleted) {
        StringBuffer text = toolCallId == null ? null : toolResultTexts.remove(toolCallId);
        // AgentScope 2.0.0 的 ToolResultEndEvent 无法可靠区分工具错误终态，必须结合桥接完成状态和
        // 框架生成的完整文本精确识别，不能仅依赖事件状态或模糊匹配“timeout”。
        if (!bridgeCompleted && text != null && toolTimeoutResult.contentEquals(text)) {
            markToolTimeout();
        }
    }

    /**
     * @return 本次运行是否已识别到工具执行超时
     */
    boolean isToolTimedOut() {
        return toolTimedOut.get();
    }

    /**
     * 标记响应式工具调用已被取消或线程中断，阻止迟到结果和后续调用继续生效。
     */
    void markInvocationInterrupted() {
        invocationInterrupted.set(true);
    }

    /**
     * @return 本次运行是否收到过工具调用取消或中断信号
     */
    boolean isInvocationInterrupted() {
        return invocationInterrupted.get();
    }

    /**
     * 在工具超时后以内部控制异常阻止网关调用或丢弃迟到结果。
     */
    private void throwIfToolTimedOut() {
        if (toolTimedOut.get()) {
            throw new RunAbortedException();
        }
    }

    /**
     * 在工具调用被取消或中断后以内部控制异常停止当前执行链。
     */
    private void throwIfInvocationInterrupted() {
        if (invocationInterrupted.get()) {
            throw new RunAbortedException();
        }
    }

    /**
     * 原子地执行一次 Agent 中断动作，并保留中断失败供异常映射阶段检查。
     *
     * <p>超时事件、基础设施失败和外层异常处理都可能竞争触发中断；只允许首次调用真正执行，
     * 避免对 AgentScope 上下文重复发出中断。</p>
     *
     * @param interruptAction 最多执行一次的 AgentScope 中断动作
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
     * @return 首次 Agent 中断失败；中断未失败时返回 {@code null}
     */
    RuntimeException interruptFailure() {
        return interruptFailure.get();
    }

    /**
     * 表示工具调用因运行门控关闭而终止的内部控制异常。
     *
     * <p>该异常不代表普通工具业务失败，桥接器必须原样向执行器传播，不能创建误导性的失败记录。</p>
     */
    static final class RunAbortedException extends RuntimeException {

        /**
         * 创建由已记录门控状态触发的中止信号。
         */
        RunAbortedException() {
        }

        /**
         * 创建由等待公平锁时线程中断触发的中止信号。
         *
         * @param cause 原始线程中断异常
         */
        RunAbortedException(InterruptedException cause) {
            super(cause);
        }
    }
}
