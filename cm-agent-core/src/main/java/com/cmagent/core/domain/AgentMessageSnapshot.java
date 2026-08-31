package com.cmagent.core.domain;

import java.util.List;
import java.util.Objects;

/** Runtime 返回的最终 assistant 消息安全快照。 */
public record AgentMessageSnapshot(
        String replyId,
        String senderName,
        List<MessageContentBlock> contentBlocks
) {
    public AgentMessageSnapshot {
        if (replyId == null || replyId.isBlank()) {
            throw new IllegalArgumentException("replyId 不能为空");
        }
        senderName = senderName == null || senderName.isBlank() ? null : senderName;
        Objects.requireNonNull(contentBlocks, "contentBlocks 不能为空");
        contentBlocks = List.copyOf(contentBlocks);
        ConversationMessageDraft.validateBlocks(MessageRole.ASSISTANT, contentBlocks);
    }
}
