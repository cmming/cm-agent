package com.cmagent.core.runtime;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;

import java.time.Instant;
import java.util.List;

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
}
