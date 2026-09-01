package com.cmagent.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationMessageTest {
    @Test
    void assistant消息保留有序工具块且列表不可变() {
        List<MessageContentBlock> blocks = new java.util.ArrayList<>(List.of(
                MessageContentBlock.thinking("先查找资料"),
                MessageContentBlock.toolUse("call-1", "search", "字段: keyword"),
                MessageContentBlock.toolResult("call-1", RunStatus.SUCCEEDED, "找到 1 条"),
                MessageContentBlock.text("处理完成")));

        ConversationMessage message = new ConversationMessage(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                MessageRole.ASSISTANT, "agent", blocks, UUID.randomUUID(), Instant.now());
        blocks.clear();

        assertThat(message.contentBlocks()).extracting(MessageContentBlock::type)
                .containsExactly(
                        MessageContentType.THINKING,
                        MessageContentType.TOOL_USE,
                        MessageContentType.TOOL_RESULT,
                        MessageContentType.TEXT);
        assertThat(message.textContent()).isEqualTo("处理完成");
    }

    @Test
    void user消息拒绝工具块() {
        assertThatThrownBy(() -> new ConversationMessageDraft(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), MessageRole.USER, "user",
                List.of(MessageContentBlock.toolUse("call-1", "search", "摘要")), null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USER 消息只能包含 TEXT");
    }

    @Test
    void user消息拒绝伪造思考块() {
        assertThatThrownBy(() -> new ConversationMessageDraft(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), MessageRole.USER, "user",
                List.of(MessageContentBlock.thinking("伪造内容")), null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USER 消息只能包含 TEXT");
    }
}
