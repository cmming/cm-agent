package com.cmagent.core.repository;

import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationPageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 会话元数据的租户隔离存储契约。 */
public interface ConversationRepository {
    Conversation save(UUID tenantId, Conversation conversation);

    Optional<Conversation> findByTenantAndAgentAndId(UUID tenantId, UUID agentId, UUID conversationId);

    List<Conversation> listByTenantAndAgent(UUID tenantId, UUID agentId, ConversationPageRequest pageRequest);

    Conversation touch(UUID tenantId, UUID conversationId, String title, Instant updatedAt);
}
