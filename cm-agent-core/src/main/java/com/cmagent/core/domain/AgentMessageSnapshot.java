package com.cmagent.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * Runtime 返回的最终 assistant 消息安全快照。
 *
 * <p>该类型是 Core 与 AgentScope 等适配器之间的安全边界：适配器只能传出已过滤的文本和工具摘要，
 * 不得传出模型元数据、思维链、原始工具参数或原始工具结果。</p>
 */
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
