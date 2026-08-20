package com.cmagent.core.runtime;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 定义执行一次 Agent 运行并返回结构化结果的运行时契约。
 */
public interface AgentRuntime {

    /**
     * 执行一次 Agent 运行并返回结构化结果。
      *
      * @param request 当前运行或工具调用请求
     */
    AgentRunResult run(AgentRunRequest request);

    /**
     * 执行一次运行，并在运行时可以提供模型文本增量时通知调用方。
     *
     * <p>该默认实现保留既有 Runtime 的兼容性。无法提供增量输出的实现仍会完整执行，
     * 调用方应以最终 {@link AgentRunResult} 作为权威结果；支持流式模型的实现会覆盖此方法，
     * 使控制台能够在持久化终态前逐步展示文本。</p>
     *
     * @param request 当前运行请求
     * @param outputDeltaConsumer 接收已经形成的文本增量；实现不得传入 {@code null}
     * @return 运行完成后的终态结果
     */
    default AgentRunResult run(AgentRunRequest request, Consumer<String> outputDeltaConsumer) {
        Objects.requireNonNull(outputDeltaConsumer, "outputDeltaConsumer 不能为空");
        return run(request);
    }
}
