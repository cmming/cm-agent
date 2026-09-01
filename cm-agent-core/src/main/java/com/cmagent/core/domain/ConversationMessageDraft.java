package com.cmagent.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Repository 分配会话内序号之前的消息草稿。
 *
 * <p>调用方不能预先指定 {@code sequence}，以免绕过并发追加顺序；内容块在进入存储层前复制并按角色校验，
 * 防止客户端或适配器把不允许的工具块写入 USER、SYSTEM 消息。</p>
 *
 * @param id 草稿由调用方生成的消息标识
 * @param tenantId 消息归属租户
 * @param conversationId 目标会话标识；会话不存在或不属于该租户时追加失败
 * @param role 消息角色，约束允许的内容块类型
 * @param senderName 展示用发送者名称；空白归一化为 {@code null}
 * @param contentBlocks 有序内容块列表，至少一个且不可变
 * @param runId 关联的运行标识，可为 {@code null}
 * @param createdAt 消息创建时间
 */
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

    /**
     * 校验角色与内容块类型的组合。
     *
     * <p>ASSISTANT 保留文本与工具摘要的混合表达；{@code TOOL} 仅为后续显式工具结果消息保留，当前 Web API
     * 不允许客户端直接构造该角色。</p>
     */
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
