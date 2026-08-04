package com.cmagent.core.runtime;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;

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
}
