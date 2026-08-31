package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentRuntimeResult;
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

/** 编排会话、消息、Run 与 Runtime 的分段提交边界。 */
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

    public Conversation create(PrincipalRef principal, UUID agentId) {
        requireAgent(principal, agentId);
        Instant now = Instant.now();
        return conversationRepository.save(principal.tenantId(), new Conversation(
                UUID.randomUUID(), principal.tenantId(), agentId, Conversation.DEFAULT_TITLE,
                principal.principalId(), now, now));
    }

    public Conversation get(PrincipalRef principal, UUID agentId, UUID conversationId) {
        return conversationRepository.findByTenantAndAgentAndId(
                        principal.tenantId(), agentId, conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    public List<Conversation> list(
            PrincipalRef principal, UUID agentId, ConversationPageRequest pageRequest) {
        requireAgent(principal, agentId);
        return conversationRepository.listByTenantAndAgent(principal.tenantId(), agentId, pageRequest);
    }

    public List<ConversationMessage> messages(
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            MessagePageRequest pageRequest
    ) {
        get(principal, agentId, conversationId);
        return messageRepository.list(principal.tenantId(), conversationId, pageRequest);
    }

    public ConversationRunResult send(
            PrincipalRef principal,
            UUID agentId,
            UUID conversationId,
            String input,
            Consumer<ConversationRunStarted> startedConsumer,
            Consumer<AgentTextDelta> deltaConsumer
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
                principal, agentId, started.run(), runtimeInput, conversationId, deltaConsumer);

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

    public ConversationRunResult send(
            PrincipalRef principal, UUID agentId, UUID conversationId, String input) {
        return send(principal, agentId, conversationId, input, ignored -> {
        }, ignored -> {
        });
    }

    private void requireAgent(PrincipalRef principal, UUID agentId) {
        agentRepository.findByTenantAndId(principal.tenantId(), agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> operation) {
        if (transactionTemplate == null) {
            return operation.get();
        }
        return Objects.requireNonNull(transactionTemplate.execute(status -> operation.get()), "事务结果不能为空");
    }

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
