package com.cmagent.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Repository 分配会话内序号之前的消息草稿。 */
public record ConversationMessageDraft(
        UUID id,
        UUID tenantId,
        UUID conversationId,
        MessageRole role,
        String senderName,
        List<MessageContentBlock> contentBlocks,
        UUID runId,
        Instant createdAt
) {
    public ConversationMessageDraft {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        Objects.requireNonNull(role, "role 不能为空");
        senderName = senderName == null || senderName.isBlank() ? null : senderName;
        Objects.requireNonNull(contentBlocks, "contentBlocks 不能为空");
        if (contentBlocks.isEmpty()) {
            throw new IllegalArgumentException("消息至少包含一个内容块");
        }
        contentBlocks = List.copyOf(contentBlocks);
        validateBlocks(role, contentBlocks);
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }

    static void validateBlocks(MessageRole role, List<MessageContentBlock> blocks) {
        if ((role == MessageRole.USER || role == MessageRole.SYSTEM)
                && blocks.stream().anyMatch(block -> block.type() != MessageContentType.TEXT)) {
            throw new IllegalArgumentException(role + " 消息只能包含 TEXT 内容块");
        }
        if (role == MessageRole.TOOL
                && blocks.stream().anyMatch(block -> block.type() != MessageContentType.TOOL_RESULT)) {
            throw new IllegalArgumentException("TOOL 消息只能包含 TOOL_RESULT 内容块");
        }
    }
}
