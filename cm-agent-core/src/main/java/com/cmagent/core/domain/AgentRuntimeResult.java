package com.cmagent.core.domain;

import java.util.Objects;

/**
 * 原运行结果与可选结构化 assistant 消息的组合，避免修改既有 {@link AgentRunResult} JSON 合同。
 *
 * <p>旧 Runtime 可以只返回 {@code run}，由 {@code AgentRuntime} 默认适配层生成可选消息；支持原生消息的
 * 适配器则用 {@code assistantMessage} 保留安全内容块顺序。</p>
 *
 * @param run 运行终态，不能为 {@code null}
 * @param assistantMessage 可选 assistant 消息快照；运行失败、暂停或无最终消息时必须为 {@code null}
 * @param pendingApproval 可选待审批快照；仅 WAITING_APPROVAL 时存在
 */
public record AgentRuntimeResult(
        AgentRunResult run,
        AgentMessageSnapshot assistantMessage,
        RuntimePendingApproval pendingApproval
) {
    public AgentRuntimeResult {
        Objects.requireNonNull(run, "run 不能为空");
        if ((run.status() == RunStatus.WAITING_APPROVAL) != (pendingApproval != null)) {
            throw new IllegalArgumentException("WAITING_APPROVAL 与 pendingApproval 必须同时存在");
        }
        if (pendingApproval != null && assistantMessage != null) {
            throw new IllegalArgumentException("等待审批时不能包含最终 assistant 消息");
        }
    }

    /** 兼容既有终态 Runtime 的便利构造器。 */
    public AgentRuntimeResult(AgentRunResult run, AgentMessageSnapshot assistantMessage) {
        this(run, assistantMessage, null);
    }
}
