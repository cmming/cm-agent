package com.cmagent.core.repository;

import com.cmagent.core.domain.ConversationMessage;
import com.cmagent.core.domain.ConversationMessageDraft;
import com.cmagent.core.domain.MessagePageRequest;

import java.util.List;
import java.util.UUID;

/** 会话内消息顺序追加和分页读取契约。 */
public interface ConversationMessageRepository {
    ConversationMessage append(UUID tenantId, ConversationMessageDraft draft);

    List<ConversationMessage> list(UUID tenantId, UUID conversationId, MessagePageRequest pageRequest);

    List<ConversationMessage> listRecent(UUID tenantId, UUID conversationId, int limit);
}
