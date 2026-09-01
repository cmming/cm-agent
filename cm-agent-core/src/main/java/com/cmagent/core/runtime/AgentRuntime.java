package com.cmagent.core.runtime;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.AgentMessageSnapshot;
import com.cmagent.core.domain.AgentRuntimeResult;
import com.cmagent.core.domain.AgentTextDelta;
import com.cmagent.core.domain.MessageContentBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 定义执行一次 Agent 运行并返回结构化结果的运行时契约。
 *
 * <p>运行时属于可替换 SPI：本地与测试使用 {@code FakeAgentRuntime}，真实模型执行由
 * {@code cm-agent-agentscope-adapter} 落地。实现方必须遵守的边界：
 * 租户与主体以请求中已校验的字段为准，不得接受客户端覆盖；模型凭据只能经
 * {@link ModelCredentialProvider} 解析，API Key 不得进入结果、日志或审计。</p>
 */
public interface AgentRuntime {

    /**
     * 执行一次 Agent 运行并返回结构化结果。
     *
     * @param request 当前运行请求，其租户一致性已在构造期校验
     * @return 运行终态，包含输出与工具调用摘要
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

    /**
     * 执行并返回结构化 assistant 消息。旧 Runtime 无需修改，默认实现会从既有结果构造安全内容块。
     *
     * @param request 当前运行请求
     * @param deltaConsumer 接收带消息关联键的文本增量
     * @return 运行终态和可选 assistant 消息快照
     */
    default AgentRuntimeResult runStructured(
            AgentRunRequest request,
            Consumer<AgentTextDelta> deltaConsumer
    ) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(deltaConsumer, "deltaConsumer 不能为空");
        AgentRunResult result = run(request, delta -> {
            // 旧 Runtime 可能用 null 表示“本次没有可流式输出”，兼容层不把它伪造成消息事件。
            if (delta != null && !delta.isBlank()) {
                deltaConsumer.accept(new AgentTextDelta(request.runId().toString(), "text", delta));
            }
        });
        List<MessageContentBlock> blocks = new ArrayList<>();
        for (int index = 0; index < result.toolCalls().size(); index++) {
            var toolCall = result.toolCalls().get(index);
            String toolCallId = request.runId() + ":tool:" + index;
            blocks.add(MessageContentBlock.toolUse(
                    toolCallId, toolCall.toolName(), toolCall.inputSummary()));
            String summary = toolCall.status() == com.cmagent.core.domain.RunStatus.SUCCEEDED
                    ? toolCall.outputSummary()
                    : toolCall.errorMessage();
            blocks.add(MessageContentBlock.toolResult(toolCallId, toolCall.status(), summary));
        }
        if (result.output() != null && !result.output().isBlank()) {
            blocks.add(MessageContentBlock.text(result.output()));
        }
        AgentMessageSnapshot message = blocks.isEmpty() ? null : new AgentMessageSnapshot(
                request.runId().toString(), request.agent().name(), blocks);
        return new AgentRuntimeResult(result, message);
    }

    /** 非流式结构化执行的便利入口。 */
    default AgentRuntimeResult runStructured(AgentRunRequest request) {
        return runStructured(request, ignored -> {
        });
    }
}
