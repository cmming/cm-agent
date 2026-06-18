package com.cmagent.core.runtime;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class FakeAgentRuntime implements AgentRuntime {

    @Override
    public AgentRunResult run(AgentRunRequest request) {
        Instant now = Instant.now();
        return new AgentRunResult(
                UUID.randomUUID(),
                RunStatus.SUCCEEDED,
                "fake-runtime: " + request.input(),
                List.of(),
                now,
                now,
                ""
        );
    }
}
