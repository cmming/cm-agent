package com.cmagent.core.runtime;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 提供回显输入的模拟 Agent 运行时，用于本地开发和快速测试。
 */
public class FakeAgentRuntime implements AgentRuntime {

    /**
     * 生成确定性的成功结果，供本地开发和不依赖真实模型的测试使用。
     *
     * @param request 当前 Agent 运行请求
     * @return 回显输入的模拟运行结果
     */
    @Override
    public AgentRunResult run(AgentRunRequest request) {
        Instant now = Instant.now();
        return new AgentRunResult(
                request.runId(),
                RunStatus.SUCCEEDED,
                "fake-runtime: " + request.input(),
                List.of(),
                now,
                now,
                ""
        );
    }

    @Override
    /**
     * 在本地模拟运行时也发送单个输出片段，使控制台调试流程可以覆盖流式协议。
     *
     * <p>模拟运行没有真实模型的分词边界，因此只发送一段完整回显；最终结果仍由父方法统一构造。</p>
     *
     * @param request 当前 Agent 运行请求
     * @param outputDeltaConsumer 接收模拟输出片段的消费者
     * @return 运行完成后的模拟结果
     */
    public AgentRunResult run(AgentRunRequest request, Consumer<String> outputDeltaConsumer) {
        Objects.requireNonNull(outputDeltaConsumer, "outputDeltaConsumer 不能为空");
        outputDeltaConsumer.accept("fake-runtime: " + request.input());
        return run(request);
    }
}
