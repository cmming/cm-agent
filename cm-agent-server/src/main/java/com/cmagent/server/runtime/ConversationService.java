package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentRuntimeResult;
import com.cmagent.core.domain.AgentProgressEvent;
import com.cmagent.core.domain.AgentTextDelta;
import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationMessage;
import com.cmagent.core.domain.ConversationMessageDraft;
import com.cmagent.core.domain.ConversationPageRequest;
import com.cmagent.core.domain.ConversationRunResult;
import com.cmagent.core.domain.MessageContentBlock;
import com.cmagent.core.domain.MessagePageRequest;
import com.cmagent.core.domain.MessageRole;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ConversationMessageRepository;
import com.cmagent.core.repository.ConversationRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 编排会话、消息、Run 与 Runtime 的分段提交边界。
 *
 * <p>USER 消息和 {@code RUNNING} Run 在短事务中共同提交，随后才调用模型；模型调用绝不持有数据库事务。
 * 最终 assistant 快照存在时另行追加消息，因此模型或基础设施失败不会伪造 assistant 回复，但已成功提交的
 * USER 消息会作为可追溯会话事实保留。</p>
 */
@Service
public class ConversationService {
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final AgentDefinitionRepository agentRepository;
    private final RunRepository runRepository;
    private final RunPersistenceService runPersistenceService;
    private final RunExecutionService executionService;
    private final ConversationPromptComposer promptComposer;
    private final SensitiveDataRedactor redactor;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建会话编排服务。
     *
     * @param conversationRepository 会话元数据仓储
     * @param messageRepository 会话消息仓储
     * @param agentRepository 用于在可信租户内校验 Agent
     * @param runRepository 查询历史消息关联 Run 的终态
     * @param runPersistenceService 创建并收口运行记录
     * @param executionService 调用受治理 Runtime
     * @param promptComposer 构造受限历史上下文
     * @param redactor 在持久化和模型调用前清理文本
     * @param transactionTemplate JDBC 模式的短事务模板；内存模式可为空
     */
    public ConversationService(
            ConversationRepository conversationRepository,
            ConversationMessageRepository messageRepository,
            AgentDefinitionRepository agentRepository,
            RunRepository runRepository,
            RunPersistenceService runPersistenceService,
            RunExecutionService executionService,
            ConversationPromptComposer promptComposer,
            SensitiveDataRedactor redactor,
            @Nullable TransactionTemplate transactionTemplate
    ) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.runPersistenceService = runPersistenceService;
        this.executionService = executionService;
        this.promptComposer = promptComposer;
        this.redactor = redactor;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 为当前主体在指定 Agent 下创建空会话。
     *
     * @param principal 已认证主体，租户只能从该可信上下文取得
     * @param agentId 目标 Agent 标识
     * @return 使用默认标题的新会话
     * @throws ResponseStatusException Agent 不属于当前租户或不存在时抛出 404
     */
    public Conversation create(PrincipalRef principal, UUID agentId) {
        requireAgent(principal, agentId);
        Instant now = Instant.now();
        return conversationRepository.save(principal.tenantId(), new Conversation(
                UUID.randomUUID(), principal.tenantId(), agentId, Conversation.DEFAULT_TITLE,
                principal.principalId(), now, now));
    }

    /**
     * 读取当前租户、指定 Agent 下的会话。
     *
     * <p>跨租户和跨 Agent 均统一返回 404，避免泄露会话是否存在。</p>
     */
    public Conversation get(PrincipalRef principal, UUID agentId, UUID conversationId) {
        return conversationRepository.findByTenantAndAgentAndId(
                        principal.tenantId(), agentId, conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    /**
     * 按稳定复合游标列出当前主体可见的 Agent 会话。
     */
    public List<Conversation> list(
            PrincipalRef principal, UUID agentId, ConversationPageRequest pageRequest) {
        requireAgent(principal, agentId);
        return conversationRepository.listByTenantAndAgent(principal.tenantId(), agentId, pageRequest);
    }

    /**
     * 在读取消息前校验会话归属，防止消息 Repository 仅按会话标识查询时绕过 Agent 边界。
     */
    public List<ConversationMessage> messages(
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            MessagePageRequest pageRequest
    ) {
        get(principal, agentId, conversationId);
        return messageRepository.list(principal.tenantId(), conversationId, pageRequest);
    }

    /**
     * 追加用户消息、执行本轮 Runtime，并在收到最终安全快照时追加 assistant 消息。
     *
     * <p>启动回调只会在 USER 消息和 {@code RUNNING} Run 已成功提交后触发，因此 SSE 客户端可安全回放其
     * 关联标识。当前 USER 消息不会进入本轮历史，避免输入重复；历史关联失败 Run 会传给提示词编排器作
     * 显式标记。Runtime 的异常由既有运行服务负责诊断和失败收口，本方法不会补写虚假的 assistant 消息。</p>
     *
     * @param principal 已认证主体，提供唯一可信 tenant
     * @param agentId 当前会话绑定的 Agent 标识
     * @param conversationId 目标会话标识
     * @param input 原始用户输入
     * @param startedConsumer USER 消息与 Run 提交后的通知消费者
     * @param deltaConsumer Runtime 输出已脱敏文本增量的消费者
     * @return 用户消息、运行结果与可选 assistant 消息的关联结果
     */
    public ConversationRunResult send(
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            String input,
            Consumer<ConversationRunStarted> startedConsumer,
            Consumer<AgentTextDelta> deltaConsumer
    ) {
        return send(principal, agentId, conversationId, input, startedConsumer, deltaConsumer, ignored -> {
        });
    }

    /**
     * 追加用户消息、执行本轮 Runtime，并向当前观察者发送受控思考与工具进度。
     *
     * <p>进度事件不会参与消息序号分配；只有 Runtime 最终安全快照会随 assistant 消息持久化，
     * 因此浏览器断开或临时事件丢失不会改变会话历史的权威结果。</p>
     *
     * @param principal 已认证主体，提供唯一可信 tenant
     * @param agentId 当前会话绑定的 Agent 标识
     * @param conversationId 目标会话标识
     * @param input 原始用户输入
     * @param startedConsumer USER 消息与 Run 提交后的通知消费者
     * @param deltaConsumer Runtime 输出已脱敏文本增量的消费者
     * @param progressConsumer Runtime 输出已脱敏执行进度的消费者
     * @return 用户消息、运行结果与可选 assistant 消息的关联结果
     */
    public ConversationRunResult send(
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            String input,
            Consumer<ConversationRunStarted> startedConsumer,
            Consumer<AgentTextDelta> deltaConsumer,
            Consumer<AgentProgressEvent> progressConsumer
    ) {
        Conversation conversation = get(principal, agentId, conversationId);
        String safeInput = redactor.redact(input);
        UUID runId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        StartedState started = inTransaction(() -> {
            RunRecord run = runPersistenceService.start(principal, agentId, safeInput, runId);
            ConversationMessage userMessage = messageRepository.append(principal.tenantId(),
                    new ConversationMessageDraft(
                            userMessageId, principal.tenantId(), conversationId, MessageRole.USER,
                            principal.displayName(), List.of(MessageContentBlock.text(safeInput)), runId, Instant.now()));
            if (Conversation.DEFAULT_TITLE.equals(conversation.title())) {
                conversationRepository.touch(
                        principal.tenantId(), conversationId, titleFrom(safeInput), userMessage.createdAt());
            }
            return new StartedState(run, userMessage);
        });
        startedConsumer.accept(new ConversationRunStarted(
                conversationId, runId, started.userMessage().id()));

        List<ConversationMessage> recent = messageRepository.listRecent(
                principal.tenantId(), conversationId, ConversationPromptComposer.MAX_HISTORY_MESSAGES + 1);
        List<ConversationMessage> history = recent.stream()
                .filter(message -> message.sequence() < started.userMessage().sequence())
                .toList();
        Set<UUID> failedRunIds = history.stream()
                .map(ConversationMessage::runId)
                .filter(Objects::nonNull)
                .distinct()
                .filter(historyRunId -> runRepository.findByTenantAndAgentAndId(
                                principal.tenantId(), agentId, historyRunId)
                        .map(run -> run.status() == RunStatus.FAILED)
                        .orElse(false))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String runtimeInput = promptComposer.compose(history, safeInput, failedRunIds);
        AgentRuntimeResult runtimeResult = executionService.runPrepared(
                principal, agentId, started.run(), runtimeInput, conversationId, deltaConsumer, progressConsumer);

        ConversationMessage assistantMessage = null;
        if (runtimeResult.assistantMessage() != null) {
            assistantMessage = messageRepository.append(principal.tenantId(), new ConversationMessageDraft(
                    UUID.randomUUID(), principal.tenantId(), conversationId, MessageRole.ASSISTANT,
                    runtimeResult.assistantMessage().senderName(), runtimeResult.assistantMessage().contentBlocks(),
                    runId, Instant.now()));
        }
        return new ConversationRunResult(
                conversationId,
                userMessageId,
                assistantMessage == null ? null : assistantMessage.id(),
                runtimeResult.run(),
                assistantMessage);
    }

    /**
     * 同步发送便利入口，不订阅启动或增量事件。
     */
    public ConversationRunResult send(
            PrincipalRef principal, UUID agentId, UUID conversationId, String input) {
        return send(principal, agentId, conversationId, input, ignored -> {
        }, ignored -> {
        });
    }

    /** 在执行创建或列表前验证 Agent 属于当前可信租户。 */
    private void requireAgent(PrincipalRef principal, UUID agentId) {
        agentRepository.findByTenantAndId(principal.tenantId(), agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
    }

    /**
     * 在 JDBC 模式中执行短事务；内存模式没有事务模板时直接执行。
     *
     * <p>调用方不得把 Runtime 或外部网络操作传入此方法，避免长时间占用数据库锁。</p>
     */
    private <T> T inTransaction(java.util.function.Supplier<T> operation) {
        if (transactionTemplate == null) {
            return operation.get();
        }
        return Objects.requireNonNull(transactionTemplate.execute(status -> operation.get()), "事务结果不能为空");
    }

    /**
     * 从首条用户输入生成标题，并按 Unicode code point 截断，避免截断代理对。
     */
    private static String titleFrom(String input) {
        String normalized = input.strip().replaceAll("\\s+", " ");
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= 40) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, 40));
    }

    private record StartedState(RunRecord run, ConversationMessage userMessage) {
    }

    public record ConversationRunStarted(UUID conversationId, UUID runId, UUID userMessageId) {
    }
}
