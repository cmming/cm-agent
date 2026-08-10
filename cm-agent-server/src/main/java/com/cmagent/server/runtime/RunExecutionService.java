package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.ToolCallRecord;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.ToolAuthorizationPolicy;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
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
/** 编排 Agent 单轮运行，连接运行时、工具治理、持久化和输出脱敏边界。 */
public class RunExecutionService {
    private static final String CONTROLLED_FAILURE = "Agent 运行失败";
    private static final Logger log = LoggerFactory.getLogger(RunExecutionService.class);

    private final AgentRuntime runtime;
    private final AgentDefinitionRepository agentRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final ToolDefinitionRepository toolRepository;
    private final ToolGrantRepository grantRepository;
    private final ToolAuthorizationPolicy toolAuthorizationPolicy;
    private final RunPersistenceService persistenceService;
    private final SensitiveDataRedactor redactor;
    private final ErrorDiagnosticLogger diagnosticLogger;

    @Autowired
    /**
     * 创建 {@code RunExecutionService} 实例并保存其运行所需依赖。
     *
     * @param runtime 执行 Agent 请求的运行时实现
     * @param agentRepository 负责访问领域数据的仓储。
     * @param modelConfigRepository 负责访问领域数据的仓储。
     * @param toolRepository 负责访问领域数据的仓储。
     * @param grantRepository 负责访问领域数据的仓储。
     * @param toolAuthorizationPolicy 校验 Agent 工具授权关系的策略
     * @param persistenceService 负责当前业务流程的服务。
     * @param redactor 负责清理敏感文本的脱敏器。
     */
    public RunExecutionService(
            AgentRuntime runtime,
            AgentDefinitionRepository agentRepository,
            ModelConfigRepository modelConfigRepository,
            ToolDefinitionRepository toolRepository,
            ToolGrantRepository grantRepository,
            ToolAuthorizationPolicy toolAuthorizationPolicy,
            RunPersistenceService persistenceService,
            SensitiveDataRedactor redactor,
            ErrorDiagnosticLogger diagnosticLogger
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为空");
        this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository 不能为空");
        this.modelConfigRepository = Objects.requireNonNull(modelConfigRepository, "modelConfigRepository 不能为空");
        this.toolRepository = Objects.requireNonNull(toolRepository, "toolRepository 不能为空");
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository 不能为空");
        this.toolAuthorizationPolicy = Objects.requireNonNull(toolAuthorizationPolicy, "toolAuthorizationPolicy 不能为空");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService 不能为空");
        this.redactor = Objects.requireNonNull(redactor, "redactor 不能为空");
        this.diagnosticLogger = Objects.requireNonNull(diagnosticLogger, "diagnosticLogger 不能为空");
    }

    /**
     * 执行 Agent 的单轮运行，并持久化运行状态和工具调用结果。
     *
     * @param principal 当前认证主体
     * @param agentId   待运行的 Agent 标识
     * @param input     用户输入
     * @return Agent 运行结果
     * @throws ResponseStatusException   Agent 不存在、未启用或不可运行时抛出
     * @throws RuntimeExecutionException 运行时或受治理工具调用失败时抛出
     */
    public AgentRunResult run(PrincipalRef principal, UUID agentId, String input) {
        AgentDefinition agent = agentRepository.findByTenantAndId(principal.tenantId(), agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
        if (!agent.enabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent 已禁用");
        }
        ModelConfig modelConfig = modelConfigRepository
                .findByTenantAndId(principal.tenantId(), agent.modelProviderId())
                .filter(ModelConfig::enabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型配置不可用"));
        List<ToolDefinition> authorizedTools = authorizedTools(principal, agent);
        RunRecord runningRun = persistenceService.start(principal, agent.id(), input);

        AgentRunResult runtimeResult;
        try {
            runtimeResult = runtime.run(new AgentRunRequest(
                    runningRun.id(), principal.tenantId(), agent, modelConfig, principal, input, authorizedTools
            ));
        } catch (AuditPersistenceException auditFailure) {
            bestEffortFailureClosure(principal, runningRun);
            throw auditFailure;
        } catch (DataAccessException dataFailure) {
            bestEffortFailureClosure(principal, runningRun);
            throw dataFailure;
        } catch (RuntimeException runtimeFailure) {
            diagnosticLogger.error(new ErrorDiagnosticLogger.DiagnosticContext(
                    runningRun.id().toString(), "AGENT_RUNTIME", "RUNTIME_EXECUTION_FAILED",
                    principal.tenantId().toString(), principal.principalId(), agent.id().toString(),
                    runningRun.id().toString(), "-", "-", "AGENT"
            ), runtimeFailure);
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

    /**
     * 尽最大努力将异常运行收口为失败状态。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param runningRun 已经持久化且状态为 RUNNING 的记录。
     */
    private void bestEffortFailureClosure(PrincipalRef principal, RunRecord runningRun) {
        try {
            persistenceService.completeFailure(principal, runningRun);
        } catch (RuntimeException failureClosureFailure) {
            log.warn("运行失败收口未完成。runId={}, reason={}",
                    runningRun.id(), redactor.redact(failureClosureFailure.getMessage()));
        }
    }

    /**
     * 尽最大努力追加运行失败审计。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param runningRun 已经持久化且状态为 RUNNING 的记录。
     */
    private void bestEffortFailureAudit(PrincipalRef principal, RunRecord runningRun) {
        persistenceService.appendFailureAudit(principal, runningRun);
    }

    /**
     * 筛选当前主体获准在本次运行中使用的工具。
     *
     * @param principal 当前认证主体，提供租户、身份和权限上下文。
     * @param agent 当前处理的 Agent 定义。
     */
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

    /**
     * 使用已持久化的运行 ID 重建响应。
     *
     * @param completedRun 已进入终态、等待持久化的运行记录
     * @param result 上一步得到的处理结果。
     */
    private AgentRunResult responseWithPersistentId(com.cmagent.core.domain.RunRecord completedRun, AgentRunResult result) {
        List<ToolCallRecord> toolCalls = result.toolCalls() == null
                ? List.of()
                : result.toolCalls().stream().map(this::redactToolCall).toList();
        return new AgentRunResult(
                completedRun.id(), completedRun.status(), redactor.redact(completedRun.output()), toolCalls,
                completedRun.startedAt(), completedRun.finishedAt(), redactor.redact(completedRun.errorMessage())
        );
    }

    /**
     * 脱敏工具调用记录中的输入、输出和错误信息。
     *
     * @param record 当前处理的运行或工具调用记录
     */
    private ToolCallRecord redactToolCall(ToolCallRecord record) {
        return new ToolCallRecord(
                record.toolId(), record.toolName(), redactor.redact(record.inputSummary()),
                redactor.redact(record.outputSummary()), record.status(), record.duration(),
                record.authorized(), redactor.redact(record.errorMessage())
        );
    }

    /**
     * 表示 {@code RuntimeExecutionException} 对应失败场景的受控异常。
     */
    public static final class RuntimeExecutionException extends RuntimeException {
        /**
         * 表示 {@code RuntimeExecutionException} 对应失败场景的受控异常。
         *
         * @param cause 触发当前失败的原始异常。
         */
        public RuntimeExecutionException(Throwable cause) {
            super(CONTROLLED_FAILURE, cause);
        }
    }
}
