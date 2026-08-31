package com.cmagent.core.domain;

import java.util.Objects;

/** 原运行结果与可选结构化 assistant 消息的组合，避免修改既有 AgentRunResult JSON 合同。 */
public record AgentRuntimeResult(AgentRunResult run, AgentMessageSnapshot assistantMessage) {
    public AgentRuntimeResult {
        Objects.requireNonNull(run, "run 不能为空");
    }
}
