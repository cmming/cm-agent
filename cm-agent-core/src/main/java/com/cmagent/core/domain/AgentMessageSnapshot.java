package com.cmagent.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * Runtime 返回的最终 assistant 消息安全快照。
 *
 * <p>该类型是 Core 与 AgentScope 等适配器之间的安全边界：适配器只能传出已过滤的文本和工具摘要，
 * 不得传出模型元数据、思维链、原始工具参数或原始工具结果。</p>
 *
 * @param replyId 回复消息标识，同一运行内稳定
 * @param senderName 展示用发送者名称；空白归一化为 {@code null}
 * @param contentBlocks 有序安全内容块，按角色校验且不可变
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
