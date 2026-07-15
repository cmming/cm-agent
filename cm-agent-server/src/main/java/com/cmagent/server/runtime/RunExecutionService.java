package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.ToolCallRecord;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.ToolAuthorizationPolicy;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class RunExecutionService {
    private static final String CONTROLLED_FAILURE = "Agent 运行失败";
    private static final Logger log = LoggerFactory.getLogger(RunExecutionService.class);

    private final AgentRuntime runtime;
    private final AgentDefinitionRepository agentRepository;
    private final ToolDefinitionRepository toolRepository;
    private final ToolGrantRepository grantRepository;
    private final ToolAuthorizationPolicy toolAuthorizationPolicy;
    private final RunPersistenceService persistenceService;
    private final SensitiveDataRedactor redactor;

    @Autowired
    public RunExecutionService(
            AgentRuntime runtime,
            AgentDefinitionRepository agentRepository,
            ToolDefinitionRepository toolRepository,
            ToolGrantRepository grantRepository,
            ToolAuthorizationPolicy toolAuthorizationPolicy,
            RunPersistenceService persistenceService,
            SensitiveDataRedactor redactor
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
        this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository 不能为空");
        this.toolRepository = Objects.requireNonNull(toolRepository, "toolRepository 不能为空");
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository 不能为空");
        this.toolAuthorizationPolicy = Objects.requireNonNull(toolAuthorizationPolicy, "toolAuthorizationPolicy 不能为空");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService 不能为空");
        this.redactor = Objects.requireNonNull(redactor, "redactor 不能为空");
    }

    public AgentRunResult run(PrincipalRef principal, UUID agentId, String input) {
        AgentDefinition agent = agentRepository.findByTenantAndId(principal.tenantId(), agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
        if (!agent.enabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent 已禁用");
        }
        List<ToolDefinition> authorizedTools = authorizedTools(principal, agent);
        RunRecord runningRun = persistenceService.start(principal, agent.id(), input);

        AgentRunResult runtimeResult;
        try {
            runtimeResult = runtime.run(new AgentRunRequest(
                    principal.tenantId(), agent.id(), agent, principal, input, authorizedTools
            ));
        } catch (AuditPersistenceException auditFailure) {
            bestEffortFailureClosure(principal, runningRun);
            throw auditFailure;
        } catch (DataAccessException dataFailure) {
            bestEffortFailureClosure(principal, runningRun);
            throw dataFailure;
        } catch (RuntimeException runtimeFailure) {
            try {
                persistenceService.completeFailure(principal, runningRun);
            } catch (AuditPersistenceException | DataAccessException failureClosureFailure) {
                failureClosureFailure.addSuppressed(runtimeFailure);
                throw failureClosureFailure;
            } catch (RuntimeException failureClosureFailure) {
                log.warn("运行失败收口未完成。runId={}, reason={}",
                        runningRun.id(), redactor.redact(failureClosureFailure.getMessage()));
            }
            try {
                bestEffortFailureAudit(principal, runningRun);
            } catch (AuditPersistenceException auditFailure) {
                auditFailure.addSuppressed(runtimeFailure);
                throw auditFailure;
            }
            throw new RuntimeExecutionException(runtimeFailure);
        }

        var completedRun = persistenceService.complete(
                principal, runningRun, runtimeResult, authorizedTools
        );
        return responseWithPersistentId(completedRun, runtimeResult);
    }

    private void bestEffortFailureClosure(PrincipalRef principal, RunRecord runningRun) {
        try {
            persistenceService.completeFailure(principal, runningRun);
        } catch (RuntimeException failureClosureFailure) {
            log.warn("运行失败收口未完成。runId={}, reason={}",
                    runningRun.id(), redactor.redact(failureClosureFailure.getMessage()));
        }
    }

    private void bestEffortFailureAudit(PrincipalRef principal, RunRecord runningRun) {
        persistenceService.appendFailureAudit(principal, runningRun);
    }

    private List<ToolDefinition> authorizedTools(PrincipalRef principal, AgentDefinition agent) {
        List<ToolGrant> grants = grantRepository.listByTenantAndAgent(principal.tenantId(), agent.id());
        Map<UUID, ToolDefinition> tools = new LinkedHashMap<>();
        for (ToolGrant grant : grants) {
            if (!grant.granted() || !principal.tenantId().equals(grant.tenantId())) {
                continue;
            }
            toolRepository.findByTenantAndId(principal.tenantId(), grant.toolId())
                    .ifPresent(tool -> {
                        AuthorizationDecision decision = toolAuthorizationPolicy.check(principal, agent.id(), tool, grants);
                        if (decision.allowed()) {
                            tools.putIfAbsent(tool.id(), tool);
                        }
                    });
        }
        return new ArrayList<>(tools.values());
    }

    private AgentRunResult responseWithPersistentId(com.cmagent.core.domain.RunRecord completedRun, AgentRunResult result) {
        List<ToolCallRecord> toolCalls = result.toolCalls() == null
                ? List.of()
                : result.toolCalls().stream().map(this::redactToolCall).toList();
        return new AgentRunResult(
                completedRun.id(), completedRun.status(), redactor.redact(completedRun.output()), toolCalls,
                completedRun.startedAt(), completedRun.finishedAt(), redactor.redact(completedRun.errorMessage())
        );
    }

    private ToolCallRecord redactToolCall(ToolCallRecord record) {
        return new ToolCallRecord(
                record.toolId(), record.toolName(), redactor.redact(record.inputSummary()),
                redactor.redact(record.outputSummary()), record.status(), record.duration(),
                record.authorized(), redactor.redact(record.errorMessage())
        );
    }

    public static final class RuntimeExecutionException extends RuntimeException {
        public RuntimeExecutionException(Throwable cause) {
            super(CONTROLLED_FAILURE, cause);
        }
    }
}
