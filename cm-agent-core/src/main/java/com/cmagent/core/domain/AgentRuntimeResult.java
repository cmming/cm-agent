package com.cmagent.core.domain;

import java.util.Objects;

/**
 * 原运行结果与可选结构化 assistant 消息的组合，避免修改既有 {@link AgentRunResult} JSON 合同。
 *
 * <p>旧 Runtime 可以只返回 {@code run}，由 {@code AgentRuntime} 默认适配层生成可选消息；支持原生消息的
 * 适配器则用 {@code assistantMessage} 保留安全内容块顺序。</p>
 *
 * @param run 运行终态，不能为 {@code null}
 * @param assistantMessage 可选 assistant 消息快照；运行失败或无最终消息时必须为 {@code null}
 */
public record AgentRuntimeResult(AgentRunResult run, AgentMessageSnapshot assistantMessage) {
    public AgentRuntimeResult {
        Objects.requireNonNull(run, "run 不能为空");
    }
}
