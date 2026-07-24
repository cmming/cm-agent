package com.cmagent.core.runtime;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;

import java.time.Instant;
import java.util.List;

/**
 * FakeAgentRuntime 的核心领域类型。
 */
public class FakeAgentRuntime implements AgentRuntime {

    /**
     * 执行 run 操作。
     */
    /**
     * 定义 run 操作。
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
