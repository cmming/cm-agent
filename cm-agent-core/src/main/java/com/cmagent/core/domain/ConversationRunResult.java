package com.cmagent.core.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次会话消息运行及其持久化关联结果。
 *
 * <p>USER 消息标识始终存在，因为它与运行在模型调用前共同提交；assistant 快照仅在 Runtime 返回最终安全消息时
 * 存在，运行失败或没有最终消息时不得伪造该字段。</p>
 */
public record ConversationRunResult(
        UUID conversationId,
        UUID userMessageId,
        UUID assistantMessageId,
        AgentRunResult run,
        ConversationMessage assistantMessage
) {
    public ConversationRunResult {
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        Objects.requireNonNull(userMessageId, "userMessageId 不能为空");
        Objects.requireNonNull(run, "run 不能为空");
        if ((assistantMessageId == null) != (assistantMessage == null)) {
            throw new IllegalArgumentException("assistantMessageId 与 assistantMessage 必须同时存在");
        }
    }
}
