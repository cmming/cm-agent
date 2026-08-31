package com.cmagent.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 持久化后的完整会话消息，sequence 是会话内权威顺序。 */
public record ConversationMessage(
        UUID id,
        UUID tenantId,
        UUID conversationId,
        long sequence,
        MessageRole role,
        String senderName,
        List<MessageContentBlock> contentBlocks,
        UUID runId,
        Instant createdAt
) {
    public ConversationMessage {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(conversationId, "conversationId 不能为空");
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence 必须大于 0");
        }
        Objects.requireNonNull(role, "role 不能为空");
        senderName = senderName == null || senderName.isBlank() ? null : senderName;
        Objects.requireNonNull(contentBlocks, "contentBlocks 不能为空");
        if (contentBlocks.isEmpty()) {
            throw new IllegalArgumentException("消息至少包含一个内容块");
        }
        contentBlocks = List.copyOf(contentBlocks);
        ConversationMessageDraft.validateBlocks(role, contentBlocks);
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }

    public String textContent() {
        return contentBlocks.stream()
                .filter(block -> block.type() == MessageContentType.TEXT)
                .map(MessageContentBlock::text)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }
}
