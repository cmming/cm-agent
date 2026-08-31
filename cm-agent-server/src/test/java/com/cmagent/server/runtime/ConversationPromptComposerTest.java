package com.cmagent.server.runtime;

import com.cmagent.core.domain.ConversationMessage;
import com.cmagent.core.domain.MessageContentBlock;
import com.cmagent.core.domain.MessageRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationPromptComposerTest {
    private final ConversationPromptComposer composer = new ConversationPromptComposer();

    @Test
    void 历史被明确隔离且当前输入只出现一次() {
        UUID tenantId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        List<ConversationMessage> history = List.of(
                message(tenantId, conversationId, 1, MessageRole.USER, "上一问"),
                message(tenantId, conversationId, 2, MessageRole.ASSISTANT, "上一答"));

        String prompt = composer.compose(history, "当前问题");

        assertThat(prompt).contains("<conversation-history>", "上一问", "上一答", "<current-user-message>");
        assertThat(prompt.split("当前问题", -1)).hasSize(2);
    }

    @Test
    void 无历史时保持原始当前输入() {
        assertThat(composer.compose(List.of(), "当前问题")).isEqualTo("当前问题");
    }

    @Test
    void 失败运行的用户消息会被显式标记() {
        UUID tenantId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        ConversationMessage failed = message(tenantId, conversationId, 1, MessageRole.USER, "失败问题");

        assertThat(composer.compose(List.of(failed), "继续", Set.of(failed.runId())))
                .contains("[USER] [上一轮执行失败]", "失败问题");
    }

    private static ConversationMessage message(
            UUID tenantId, UUID conversationId, long sequence, MessageRole role, String text) {
        return new ConversationMessage(
                UUID.randomUUID(), tenantId, conversationId, sequence, role, role.name(),
                List.of(MessageContentBlock.text(text)), UUID.randomUUID(), Instant.now());
    }
}
