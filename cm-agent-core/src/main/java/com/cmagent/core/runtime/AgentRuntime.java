package com.cmagent.core.runtime;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;

public interface AgentRuntime {

    AgentRunResult run(AgentRunRequest request);
}
