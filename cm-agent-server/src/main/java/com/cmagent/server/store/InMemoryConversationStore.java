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

/**
 * 本地和测试使用的会话内存仓储，不作为生产持久化方案。
 *
 * <p>同一会话通过独立锁串行化消息追加和读取，与 JDBC 实现中的会话行锁保持相同序号语义；数据不跨进程
 * 保存，服务重启后会话、消息和锁都会丢失，因此不能作为生产持久化方案。</p>
 */
public class InMemoryConversationStore implements ConversationRepository, ConversationMessageRepository {
    /** 会话来源头与首次保存同一监视器维护；绑定行丢失后不能把私有空会话降为默认共享。 */
    private final java.util.Map<UUID, Integer> workspaceProtocols = new java.util.HashMap<>();
    @Override public boolean supportsWorkspaceProtocol() { return true; }
    /** 当前会话必须真实存在；头见证缺失属于状态损坏，不能使用getOrDefault伪装旧来源。 */
    @Override public synchronized int workspaceProtocol(UUID tenantId, UUID agentId, UUID conversationId) {
        findByTenantAndAgentAndId(tenantId, agentId, conversationId).orElseThrow(() -> new NoSuchElementException("会话不存在"));
        Integer protocol = workspaceProtocols.get(conversationId);
        if (protocol == null) throw new IllegalStateException("会话来源见证缺失");
        if (protocol != 0 && protocol != 3) throw new IllegalStateException("会话来源见证不合法");
        return protocol;
    }
    /** 宿主已持空会话控制锁并完成版本校验，只改来源头，不碰消息、标题或更新时间。 */
    @Override public synchronized void updateWorkspaceProtocol(UUID tenantId, UUID agentId, UUID conversationId, int protocol) {
        requireProtocol(protocol);
        workspaceProtocol(tenantId, agentId, conversationId);
        workspaceProtocols.put(conversationId, protocol);
    }
    /** 在LIMIT前复核导入owner；旧手工装配只能使用旧接口，不伪装支持新可见分页。 */
    private final com.cmagent.core.repository.WorkspaceSelectionRepository workspaceSelections;
    /** 延迟取得运行仓储，避免运行仓储依赖会话仓储时形成构造循环；不在分页后补过滤。 */
    private final java.util.function.Supplier<com.cmagent.core.repository.RunRepository> workspaceRuns;
    /** 兼容未接入导入的旧本地测试。 */
    public InMemoryConversationStore() { this.workspaceSelections = null; this.workspaceRuns=null; }
    /** @param workspaceSelections 与会话/运行共用的可信来源元数据 */
    public InMemoryConversationStore(com.cmagent.core.repository.WorkspaceSelectionRepository workspaceSelections) { this(workspaceSelections,null); }
    /**
     * @param workspaceSelections 可信来源元数据
     * @param workspaceRuns 宿主延迟供应运行仓储，查询前必须已完成完整装配
     */
    public InMemoryConversationStore(com.cmagent.core.repository.WorkspaceSelectionRepository workspaceSelections,java.util.function.Supplier<com.cmagent.core.repository.RunRepository> workspaceRuns) {
        this.workspaceSelections=Objects.requireNonNull(workspaceSelections);this.workspaceRuns=workspaceRuns;
    }
    @Override public List<Conversation> listVisible(UUID tenantId, UUID agentId, String principalId, ConversationPageRequest pageRequest) {
        if (workspaceSelections == null) throw new UnsupportedOperationException("当前内存仓储未装配导入可见分页");
        if (workspaceRuns==null)throw new UnsupportedOperationException("当前内存仓储未装配导入来源见证过滤");
        final List<Conversation> visibleHeads;
        synchronized (this) {
            // 复制会话与头见证的同一视图后退出监视器，避免跨调用Run仓储形成C→R/R→C反锁。
            visibleHeads = conversations.values().stream().filter(c -> tenantId.equals(c.tenantId()) && agentId.equals(c.agentId()))
                    .filter(c -> workspaceProtocol(tenantId, agentId, c.id()) == 0 || principalId.equals(c.createdBy())).toList();
        }
        var sourceRuns=workspaceRuns.get();
        return visibleHeads.stream()
                .filter(c -> workspaceSelections.visibleConversation(c, principalId) && sourceRuns.visibleConversationFromWorkspace(c,principalId)).filter(c -> isBefore(c, pageRequest))
                .sorted(Comparator.comparing(Conversation::updatedAt).reversed().thenComparing(c -> c.id().toString(), Comparator.reverseOrder()))
                .limit(pageRequest.limit()).toList();
    }
    /** 在会话自身锁内检查关联消息，运行文件仍须另外验证 Run 创建者。 */
    @Override
    public Optional<Conversation> findByRun(UUID tenantId,UUID runId) {
        for(var conversation:conversations.values()) {
            if(!conversation.tenantId().equals(tenantId))continue;
            synchronized(conversationLocks.computeIfAbsent(conversation.id(),ignored->new Object())) {
                if(messages.getOrDefault(conversation.id(),List.of()).stream().anyMatch(message->runId.equals(message.runId())))
                    return Optional.of(conversation);
            }
        }
        return Optional.empty();
    }
    private final ConcurrentHashMap<UUID, Conversation> conversations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<ConversationMessage>> messages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> conversationLocks = new ConcurrentHashMap<>();

    @Override
    /**
     * 保存新会话并初始化对应的消息列表。
     */
    public Conversation save(UUID tenantId, Conversation conversation) {
        return saveWithWorkspaceProtocol(tenantId, conversation, 0);
    }

    /** 首次发布会话前先固定见证和空消息槽；重复保存不能重置已存在会话的来源。 */
    @Override public synchronized Conversation saveWithWorkspaceProtocol(UUID tenantId, Conversation conversation, int protocol) {
        requireTenant(tenantId, conversation.tenantId());
        requireProtocol(protocol);
        if (conversations.containsKey(conversation.id())) {
            throw new IllegalStateException("会话已存在");
        }
        messages.put(conversation.id(), new java.util.ArrayList<>());
        workspaceProtocols.put(conversation.id(), protocol);
        conversations.put(conversation.id(), conversation);
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
    /**
     * 在会话锁内更新标题和活动时间，避免与消息追加相互覆盖。
     */
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
    /**
     * 在单会话锁内分配连续序号、追加消息并推进活动时间。
     *
     * <p>不能在锁外根据列表大小计算序号，否则并发发送会产生重复序号；不同会话仍可并发写入。</p>
     */
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
    /**
     * 返回最近窗口的防御性副本，且保持消息的自然序号顺序。
     */
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

    @Override
    public List<ConversationMessage> listByRun(UUID tenantId, UUID conversationId, UUID runId) {
        requireConversation(tenantId, conversationId);
        synchronized (lock(conversationId)) {
            return messages.get(conversationId).stream().filter(message -> runId.equals(message.runId())).toList();
        }
    }

    @Override
    public List<UUID> activeRunCandidates(UUID tenantId, UUID conversationId) {
        requireConversation(tenantId, conversationId);
        synchronized (lock(conversationId)) {
            return messages.get(conversationId).stream().map(ConversationMessage::runId)
                    .filter(Objects::nonNull).distinct().toList();
        }
    }

    private Conversation requireConversation(UUID tenantId, UUID conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null || !tenantId.equals(conversation.tenantId())) {
            throw new NoSuchElementException("会话不存在");
        }
        return conversation;
    }

    /**
     * 为会话取得进程内锁；锁对象会随仓储生命周期保留，以保证同一会话每次访问使用同一监视器。
     */
    private Object lock(UUID conversationId) {
        return conversationLocks.computeIfAbsent(conversationId, ignored -> new Object());
    }

    private static void requireProtocol(int protocol) {
        if (protocol != 0 && protocol != 3) throw new IllegalArgumentException("会话工作区协议不合法");
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
