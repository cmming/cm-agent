package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunPageRequest;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.RunToolCall;
import com.cmagent.core.domain.RunToolCallBatch;
import com.cmagent.core.domain.ToolCallRecord;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.ToolCallRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class RunPersistenceService {
    private static final String CONTROLLED_FAILURE = "Agent 运行失败";
    private static final Logger log = LoggerFactory.getLogger(RunPersistenceService.class);

    private final RunRepository runRepository;
    private final ToolCallRepository toolCallRepository;
    private final AuditAppender auditAppender;
    private final SensitiveDataRedactor redactor;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public RunPersistenceService(
            RunRepository runRepository,
            ToolCallRepository toolCallRepository,
            AuditAppender auditAppender,
            SensitiveDataRedactor redactor,
            @Nullable TransactionTemplate transactionTemplate
    ) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository 不能为空");
        this.toolCallRepository = Objects.requireNonNull(toolCallRepository, "toolCallRepository 不能为空");
        this.auditAppender = Objects.requireNonNull(auditAppender, "auditAppender 不能为空");
        this.redactor = Objects.requireNonNull(redactor, "redactor 不能为空");
        this.transactionTemplate = transactionTemplate;
    }

    public RunRecord start(PrincipalRef principal, UUID agentId, String input) {
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        RunRecord pending = RunRecord.create(
                UUID.randomUUID(), principal.tenantId(), agentId, principal.principalId(),
                redactor.redact(input), Instant.now()
        );
        if (transactionTemplate == null) {
            // Without JDBC transactions, audit first prevents a failed audit from leaving a memory row behind.
            appendAudit(principal, pending, RunStatus.RUNNING, "Agent 运行已启动");
            return runRepository.save(principal.tenantId(), pending);
        }
        return requireResult(transactionTemplate.execute(status -> {
            RunRecord saved = runRepository.save(principal.tenantId(), pending);
            appendAudit(principal, saved, RunStatus.RUNNING, "Agent 运行已启动");
            return saved;
        }));
    }

    public RunRecord complete(
            PrincipalRef principal,
            RunRecord runningRun,
            AgentRunResult result,
            List<ToolDefinition> authorizedTools
    ) {
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(runningRun, "runningRun 不能为空");
        try {
            Objects.requireNonNull(result, "result 不能为空");
            RunStatus status = finalStatus(result.status());
            Instant finishedAt = finishedAt(runningRun, result.finishedAt());
            String output = redactor.redact(result.output());
            String errorMessage = redactor.redact(result.errorMessage());
            if (status == RunStatus.FAILED && errorMessage.isBlank()) {
                errorMessage = CONTROLLED_FAILURE;
            }
            List<RunToolCall> toolCalls = mapToolCalls(
                    principal.tenantId(), runningRun.id(), authorizedTools, result.toolCalls(), finishedAt
            );
            if (transactionTemplate == null) {
                appendAudit(principal, runningRun, status, completionMessage(status));
                RunRecord saved = runRepository.complete(
                        principal.tenantId(), runningRun.id(), status, output, errorMessage, finishedAt
                );
                toolCallRepository.saveAll(principal.tenantId(), new RunToolCallBatch(principal.tenantId(), toolCalls));
                return saved;
            }
            final String persistedError = errorMessage;
            return requireResult(transactionTemplate.execute(transactionStatus -> {
                RunRecord saved = runRepository.complete(
                        principal.tenantId(), runningRun.id(), status, output, persistedError, finishedAt
                );
                toolCallRepository.saveAll(principal.tenantId(), new RunToolCallBatch(principal.tenantId(), toolCalls));
                appendAudit(principal, saved, status, completionMessage(status));
                return saved;
            }));
        } catch (RuntimeException completionFailure) {
            try {
                completeFailure(principal, runningRun);
            } catch (RuntimeException failureClosureFailure) {
                log.warn("运行失败收口未完成。runId={}, reason={}",
                        runningRun.id(), redactor.redact(failureClosureFailure.getMessage()));
            }
            throw completionFailure;
        }
    }

    public RunRecord completeFailure(PrincipalRef principal, RunRecord runningRun) {
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(runningRun, "runningRun 不能为空");
        Instant finishedAt = finishedAt(runningRun, Instant.now());
        if (transactionTemplate == null) {
            return runRepository.complete(
                    principal.tenantId(), runningRun.id(), RunStatus.FAILED, "", CONTROLLED_FAILURE, finishedAt
            );
        }
        return requireResult(transactionTemplate.execute(transactionStatus -> runRepository.complete(
                principal.tenantId(), runningRun.id(), RunStatus.FAILED, "", CONTROLLED_FAILURE, finishedAt
        )));
    }

    public void appendFailureAudit(PrincipalRef principal, RunRecord runningRun) {
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(runningRun, "runningRun 不能为空");
        appendAudit(principal, runningRun, RunStatus.FAILED, CONTROLLED_FAILURE);
    }

    public RunDetail findDetail(UUID tenantId, UUID agentId, UUID runId) {
        RunRecord run = runRepository.findByTenantAndAgentAndId(tenantId, agentId, runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run 不存在"));
        return new RunDetail(redactRun(run), toolCallRepository.listByTenantAndRun(tenantId, runId).stream()
                .map(this::redactToolCall)
                .toList());
    }

    public List<RunRecord> list(UUID tenantId, UUID agentId, RunPageRequest pageRequest) {
        return runRepository.listByTenantAndAgent(tenantId, agentId, pageRequest).stream()
                .map(this::redactRun)
                .toList();
    }

    private RunRecord redactRun(RunRecord run) {
        return new RunRecord(
                run.id(), run.tenantId(), run.agentId(), run.principalId(), run.status(),
                redactor.redact(run.input()), redactor.redact(run.output()), redactor.redact(run.errorMessage()),
                run.startedAt(), run.finishedAt()
        );
    }

    private RunToolCall redactToolCall(RunToolCall toolCall) {
        return new RunToolCall(
                toolCall.id(), toolCall.tenantId(), toolCall.runId(), toolCall.toolId(), toolCall.toolName(),
                redactor.redact(toolCall.inputSummary()), redactor.redact(toolCall.outputSummary()),
                toolCall.status(), toolCall.authorized(), toolCall.durationMillis(),
                redactor.redact(toolCall.errorMessage()), toolCall.createdAt()
        );
    }

    private List<RunToolCall> mapToolCalls(
            UUID tenantId,
            UUID runId,
            List<ToolDefinition> authorizedTools,
            List<ToolCallRecord> records,
            Instant createdAt
    ) {
        Map<UUID, ToolDefinition> byId = new HashMap<>();
        Map<String, ToolDefinition> byName = new HashMap<>();
        Set<String> ambiguousNames = new HashSet<>();
        if (authorizedTools != null) {
            for (ToolDefinition tool : authorizedTools) {
                if (tool == null || !tenantId.equals(tool.tenantId()) || tool.id() == null || tool.name() == null) {
                    continue;
                }
                byId.put(tool.id(), tool);
                if (byName.containsKey(tool.name())) {
                    byName.remove(tool.name());
                    ambiguousNames.add(tool.name());
                } else if (!ambiguousNames.contains(tool.name())) {
                    byName.put(tool.name(), tool);
                }
            }
        }
        List<RunToolCall> mapped = new ArrayList<>();
        if (records == null) {
            return mapped;
        }
        for (ToolCallRecord record : records) {
            if (record == null || record.status() == null) {
                continue;
            }
            ToolDefinition tool = resolveTool(record, byId, byName);
            if (tool == null) {
                continue;
            }
            Long durationMillis = record.duration() == null ? null : record.duration().toMillis();
            mapped.add(new RunToolCall(
                    UUID.randomUUID(), tenantId, runId, tool.id(), tool.name(),
                    redactor.redact(record.inputSummary()), redactor.redact(record.outputSummary()),
                    record.status(), record.authorized(), durationMillis,
                    redactor.redact(record.errorMessage()), createdAt
            ));
        }
        return mapped;
    }

    private ToolDefinition resolveTool(
            ToolCallRecord record,
            Map<UUID, ToolDefinition> byId,
            Map<String, ToolDefinition> byName
    ) {
        if (record.toolId() != null) {
            ToolDefinition byToolId = byId.get(record.toolId());
            if (byToolId == null) {
                return null;
            }
            if (record.toolName() != null && !record.toolName().isBlank()
                    && !record.toolName().equals(byToolId.name())) {
                return null;
            }
            return byToolId;
        }
        if (record.toolName() == null || record.toolName().isBlank()) {
            return null;
        }
        return byName.get(record.toolName());
    }

    private void appendAudit(PrincipalRef principal, RunRecord run, RunStatus status, String message) {
        auditAppender.append(
                principal.tenantId(), principal.principalId(), "AGENT_RUN", "RUN", run.id().toString(),
                status.name(), redactor.redact(message)
        );
    }

    private static RunStatus finalStatus(RunStatus status) {
        return status == null || status == RunStatus.RUNNING ? RunStatus.FAILED : status;
    }

    private static Instant finishedAt(RunRecord run, Instant candidate) {
        if (candidate != null && !candidate.isBefore(run.startedAt())) {
            return candidate;
        }
        Instant now = Instant.now();
        return now.isBefore(run.startedAt()) ? run.startedAt() : now;
    }

    private static String completionMessage(RunStatus status) {
        return switch (status) {
            case SUCCEEDED -> "Agent 运行完成";
            case DENIED -> "Agent 运行被拒绝";
            default -> CONTROLLED_FAILURE;
        };
    }

    private static <T> T requireResult(T result) {
        return Objects.requireNonNull(result, "事务未返回结果");
    }

    public record RunDetail(RunRecord run, List<RunToolCall> toolCalls) {
        public RunDetail {
            toolCalls = List.copyOf(toolCalls);
        }
    }
}
