package com.cmagent.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 持久化后的完整会话消息，{@code sequence} 是会话内权威顺序。
 *
 * <p>时间戳只用于展示和审计，不能替代序号进行上下文重放或分页；内容块在构造时复制，防止保存后
 * 被调用方修改而破坏消息快照。</p>
 */
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

    /**
     * 取得兼容旧 {@code messages.content} 投影的全部文本内容。
     *
     * <p>工具块不参与该投影，避免将工具调用标识或摘要误当作普通消息正文；多个文本块以换行保留顺序。</p>
     *
     * @return 按内容块顺序拼接的文本；没有文本块时为空字符串
     */
    public String textContent() {
        return contentBlocks.stream()
                .filter(block -> block.type() == MessageContentType.TEXT)
                .map(MessageContentBlock::text)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }
}
