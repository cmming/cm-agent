package com.cmagent.core.domain;

import java.util.Objects;
import java.util.UUID;

/** 一次会话消息运行及其持久化关联结果。 */
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
