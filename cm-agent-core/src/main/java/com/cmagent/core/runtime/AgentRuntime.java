package com.cmagent.core.runtime;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;

/**
 * AgentRuntime 的核心领域类型。
 */
public interface AgentRuntime {

    /**
     * 定义 run 操作。
     */
    AgentRunResult run(AgentRunRequest request);
}
