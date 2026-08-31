package com.cmagent.server.store;

import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationMessage;
import com.cmagent.core.domain.ConversationMessageDraft;
import com.cmagent.core.domain.ConversationPageRequest;
import com.cmagent.core.domain.MessagePageRequest;
import com.cmagent.core.repository.ConversationMessageRepository;
import com.cmagent.core.repository.ConversationRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 本地和测试使用的会话内存仓储，不作为生产持久化方案。 */
public class InMemoryConversationStore implements ConversationRepository, ConversationMessageRepository {
    private final ConcurrentHashMap<UUID, Conversation> conversations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<ConversationMessage>> messages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> conversationLocks = new ConcurrentHashMap<>();

    @Override
    public Conversation save(UUID tenantId, Conversation conversation) {
        requireTenant(tenantId, conversation.tenantId());
        if (conversations.putIfAbsent(conversation.id(), conversation) != null) {
            throw new IllegalStateException("会话已存在");
        }
        messages.put(conversation.id(), new java.util.ArrayList<>());
        return conversation;
    }

    @Override
    public Optional<Conversation> findByTenantAndAgentAndId(
            UUID tenantId, UUID agentId, UUID conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null || !tenantId.equals(conversation.tenantId())
                || !agentId.equals(conversation.agentId())) {
            return Optional.empty();
        }
        return Optional.of(conversation);
    }

    @Override
    public List<Conversation> listByTenantAndAgent(
            UUID tenantId, UUID agentId, ConversationPageRequest pageRequest) {
        return conversations.values().stream()
                .filter(item -> tenantId.equals(item.tenantId()) && agentId.equals(item.agentId()))
                .filter(item -> isBefore(item, pageRequest))
                .sorted(Comparator.comparing(Conversation::updatedAt).reversed()
                        .thenComparing(Conversation::id, Comparator.reverseOrder()))
                .limit(pageRequest.limit())
                .toList();
    }

    @Override
    public Conversation touch(UUID tenantId, UUID conversationId, String title, Instant updatedAt) {
        Object lock = lock(conversationId);
        synchronized (lock) {
            Conversation existing = conversations.get(conversationId);
            if (existing == null || !tenantId.equals(existing.tenantId())) {
                throw new NoSuchElementException("会话不存在");
            }
            Conversation touched = new Conversation(
                    existing.id(), existing.tenantId(), existing.agentId(), title, existing.createdBy(),
                    existing.createdAt(), updatedAt);
            conversations.put(conversationId, touched);
            return touched;
        }
    }

    @Override
    public ConversationMessage append(UUID tenantId, ConversationMessageDraft draft) {
        requireTenant(tenantId, draft.tenantId());
        Object lock = lock(draft.conversationId());
        synchronized (lock) {
            Conversation conversation = conversations.get(draft.conversationId());
            if (conversation == null || !tenantId.equals(conversation.tenantId())) {
                throw new NoSuchElementException("会话不存在");
            }
            List<ConversationMessage> conversationMessages = messages.get(draft.conversationId());
            long sequence = conversationMessages.size() + 1L;
            ConversationMessage message = new ConversationMessage(
                    draft.id(), draft.tenantId(), draft.conversationId(), sequence, draft.role(),
                    draft.senderName(), draft.contentBlocks(), draft.runId(), draft.createdAt());
            conversationMessages.add(message);
            conversations.put(conversation.id(), new Conversation(
                    conversation.id(), conversation.tenantId(), conversation.agentId(), conversation.title(),
                    conversation.createdBy(), conversation.createdAt(), draft.createdAt()));
            return message;
        }
    }

    @Override
    public List<ConversationMessage> list(
            UUID tenantId, UUID conversationId, MessagePageRequest pageRequest) {
        Conversation conversation = requireConversation(tenantId, conversationId);
        Object lock = lock(conversation.id());
        synchronized (lock) {
            return messages.get(conversationId).stream()
                    .filter(message -> message.sequence() > pageRequest.afterSequence())
                    .limit(pageRequest.limit())
                    .toList();
        }
    }

    @Override
    public List<ConversationMessage> listRecent(UUID tenantId, UUID conversationId, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit 必须在 1 到 200 之间");
        }
        Conversation conversation = requireConversation(tenantId, conversationId);
        Object lock = lock(conversation.id());
        synchronized (lock) {
            List<ConversationMessage> source = messages.get(conversationId);
            return List.copyOf(source.subList(Math.max(0, source.size() - limit), source.size()));
        }
    }

    private Conversation requireConversation(UUID tenantId, UUID conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null || !tenantId.equals(conversation.tenantId())) {
            throw new NoSuchElementException("会话不存在");
        }
        return conversation;
    }

    private Object lock(UUID conversationId) {
        return conversationLocks.computeIfAbsent(conversationId, ignored -> new Object());
    }

    private static boolean isBefore(Conversation item, ConversationPageRequest request) {
        if (request.beforeUpdatedAt() == null) {
            return true;
        }
        int timeComparison = item.updatedAt().compareTo(request.beforeUpdatedAt());
        return timeComparison < 0
                || (timeComparison == 0 && item.id().compareTo(request.beforeId()) < 0);
    }

    private static void requireTenant(UUID expected, UUID actual) {
        if (!Objects.requireNonNull(expected, "tenantId 不能为空").equals(actual)) {
            throw new IllegalArgumentException("tenantId 不匹配");
        }
    }
}
