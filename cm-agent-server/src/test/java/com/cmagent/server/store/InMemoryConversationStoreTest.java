package com.cmagent.server.store;

import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationMessageDraft;
import com.cmagent.core.domain.MessageContentBlock;
import com.cmagent.core.domain.MessagePageRequest;
import com.cmagent.core.domain.MessageRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryConversationStoreTest {
    @Test
    void 同一会话按序追加并隔离租户() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        UUID tenantId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Instant now = Instant.now();
        store.save(tenantId, new Conversation(
                conversationId, tenantId, UUID.randomUUID(), Conversation.DEFAULT_TITLE,
                "tester", now, now));

        var first = store.append(tenantId, draft(tenantId, conversationId, "第一条"));
        var second = store.append(tenantId, draft(tenantId, conversationId, "第二条"));

        assertThat(first.sequence()).isEqualTo(1);
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(store.list(tenantId, conversationId, new MessagePageRequest(10, 1)))
                .extracting(message -> message.textContent())
                .containsExactly("第二条");
        assertThatThrownBy(() -> store.list(otherTenant, conversationId, new MessagePageRequest(10, 0)))
                .isInstanceOf(NoSuchElementException.class);
    }

    private static ConversationMessageDraft draft(UUID tenantId, UUID conversationId, String text) {
        return new ConversationMessageDraft(
                UUID.randomUUID(), tenantId, conversationId, MessageRole.USER, "tester",
                List.of(MessageContentBlock.text(text)), UUID.randomUUID(), Instant.now());
    }
}
