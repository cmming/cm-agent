package com.cmagent.server.runtime;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationRequest;
import com.cmagent.core.runtime.ToolInvocationResult;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.ToolAuthorizationPolicy;
import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
/** 面向 AgentScope 的工具调用网关，确保每次调用都重新经过治理链路。 */
public class GovernedToolInvocationService implements ToolInvocationGateway {
    /** 可选本机客户端能力；关闭时不注册服务也不会进入远程工具分支。 */
    private LocalClientOperationService localClientOperations;
    @org.springframework.beans.factory.annotation.Autowired
    void configureLocalClientOperations(
            org.springframework.beans.factory.ObjectProvider<LocalClientOperationService> provider) {
        localClientOperations = provider.getIfAvailable();
    }
    /** 延迟解析项目入口，避免Runtime与本机状态卷装配循环；关闭时仍不开放项目工具。 */
    private org.springframework.beans.factory.ObjectProvider<LocalProjectTools> projectTools;
    @org.springframework.beans.factory.annotation.Autowired
    void configureProjectTools(org.springframework.beans.factory.ObjectProvider<LocalProjectTools> provider) { projectTools = provider; }
    /** 已配对设备项目工具与 Server 本机项目使用独立执行器。 */
    private org.springframework.beans.factory.ObjectProvider<ClientProjectTools> clientProjectTools;
    @org.springframework.beans.factory.annotation.Autowired
    void configureClientProjectTools(
            org.springframework.beans.factory.ObjectProvider<ClientProjectTools> provider) {
        clientProjectTools = provider;
    }
    private static final String RESOURCE_TYPE = "TOOL";
    private static final String TOOL_UNAVAILABLE = "工具不可用";
    private static final String TOOL_EXECUTION_FAILED = "工具执行失败";

    private final ToolDefinitionRepository toolRepository;
    private final ToolGrantRepository grantRepository;
    private final ToolAuthorizationPolicy policy;
    private final GovernedToolExecutionService executionService;
    private final AuditAppender auditAppender;
    private final ErrorDiagnosticLogger diagnosticLogger;
    /**
     * 创建 {@code GovernedToolInvocationService} 实例并保存其运行所需依赖。
     *
     * @param toolRepository 负责访问领域数据的仓储。
     * @param grantRepository 负责访问领域数据的仓储。
     * @param policy 工具调用授权策略
     * @param executionService 执行已准备工具的服务。
     * @param auditAppender 负责追加安全审计事件的组件。
     * @param diagnosticLogger 负责记录脱敏诊断日志的组件
     */
    public GovernedToolInvocationService(
            ToolDefinitionRepository toolRepository,
            ToolGrantRepository grantRepository,
            ToolAuthorizationPolicy policy,
            GovernedToolExecutionService executionService,
            AuditAppender auditAppender,
            ErrorDiagnosticLogger diagnosticLogger
    ) {
        this.toolRepository = Objects.requireNonNull(toolRepository, "toolRepository 不能为空");
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository 不能为空");
        this.policy = Objects.requireNonNull(policy, "policy 不能为空");
        this.executionService = Objects.requireNonNull(executionService, "executionService 不能为空");
        this.auditAppender = Objects.requireNonNull(auditAppender, "auditAppender 不能为空");
        this.diagnosticLogger = Objects.requireNonNull(diagnosticLogger, "diagnosticLogger 不能为空");
    }

    @Override
    /**
     * 接收 AgentScope 的工具调用，并在每次调用时重新执行租户、权限和工具状态校验。
     *
     * @param request 包含主体、工具和输入的调用请求
     * @return 受治理的工具调用结果
     * @throws RuntimeException 治理失败、工具执行失败或审计失败时抛出
     */
    public ToolInvocationResult invoke(ToolInvocationRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        var localKind = LocalClientToolCatalog.find(request.tenantId(), request.toolId());
        if (localKind.isPresent()) {
            if (localClientOperations == null) {
                appendAudit(request, "TOOL_CALL_DENIED", "DENIED", "本机客户端功能未启用");
                return ToolInvocationResult.deniedProtected("本机客户端功能未启用");
            }
            ToolInvocationResult localResult = localClientOperations.invoke(request, localKind.orElseThrow());
            if (!localResult.authorized()) {
                auditAppender.accessDenied(request.principal(), RESOURCE_TYPE, request.toolId().toString(),
                        "local-client:invoke", localResult.errorMessage());
            }
            return localResult;
        }
        ToolDefinition tool = toolRepository.findByTenantAndId(request.tenantId(), request.toolId())
                .filter(definition -> isVisibleDefinition(request, definition))
                .orElse(null);
        if (tool == null) {
            appendAudit(request, "TOOL_CALL_FAILED", "FAILED", "工具调用失败");
            return ToolInvocationResult.failed(TOOL_UNAVAILABLE);
        }

        List<ToolGrant> grants = grantRepository.listByTenantAndAgent(request.tenantId(), request.agentId());
        AuthorizationDecision decision = policy.check(request.principal(), request.agentId(), tool, grants);
        if (!decision.allowed()) {
            auditAppender.accessDenied(
                    request.principal(), RESOURCE_TYPE, request.toolId().toString(), "tool:invoke", decision.reason()
            );
            return ToolInvocationResult.denied(decision.reason());
        }

        // 项目工具仍先经过相同的租户/定义/grant策略；专用入口额外核对固定Run和真实HIGH决定。
        // 不能注册普通ToolRegistry执行器，否则独立调试或MCP可能绕过项目Run上下文。
        if (LocalProjectToolCatalog.managed(request.tenantId(), request.toolId())) {
            var projects = projectTools == null ? null : projectTools.getIfAvailable();
            if(projects==null){appendAudit(request,"TOOL_CALL_DENIED","DENIED","本地项目功能未启用");return ToolInvocationResult.deniedProtected("本地项目功能未启用");}
            appendAudit(request,"TOOL_CALL_STARTED","RUNNING","项目工具调用已开始");
            try {
                var result=projects.invoke(request,tool);
                appendAudit(request,result.success()?"TOOL_CALL_COMPLETED":"TOOL_CALL_FAILED",result.success()?"SUCCEEDED":result.authorized()?"FAILED":"DENIED","项目工具受控结果");
                return result;
            }catch(com.cmagent.core.runtime.ToolInvocationInfrastructureException failure){
                // 项目边界已写同号安全诊断，这里只写工具元数据审计，继续抛出以关闭Runtime派发门控。
                appendAudit(request,"TOOL_CALL_FAILED","FAILED","项目工具支撑设施不可用");throw failure;
            }
        }

        if (ClientProjectToolCatalog.managed(request.tenantId(), request.toolId())) {
            var clientProjects = clientProjectTools == null ? null
                    : clientProjectTools.getIfAvailable();
            if (clientProjects == null) {
                appendAudit(request, "TOOL_CALL_DENIED", "DENIED", "已配对设备项目功能未启用");
                return ToolInvocationResult.deniedProtected("已配对设备项目功能未启用");
            }
            appendAudit(request, "TOOL_CALL_STARTED", "RUNNING", "设备项目工具调用已开始");
            try {
                var result = clientProjects.invoke(request, tool);
                appendAudit(request, result.success() ? "TOOL_CALL_COMPLETED" : "TOOL_CALL_FAILED",
                        result.success() ? "SUCCEEDED" : result.authorized() ? "FAILED" : "DENIED",
                        "设备项目工具受控结果");
                return result;
            } catch (com.cmagent.core.runtime.ToolInvocationInfrastructureException failure) {
                appendAudit(request, "TOOL_CALL_FAILED", "FAILED", "设备项目工具支撑设施不可用");
                throw failure;
            }
        }

        ToolExecutionRequest executionRequest = new ToolExecutionRequest(
                request.tenantId(), request.agentId(), request.principal(), request.runId(),
                request.toolCallId(), request.toolId(), request.inputJson()
        );
        GovernedToolExecutionService.PreparedToolExecution prepared = executionService.prepare(tool, executionRequest);
        if (!prepared.ready()) {
            appendAudit(request, "TOOL_CALL_FAILED", "FAILED", "工具调用失败");
            return ToolInvocationResult.failed(TOOL_UNAVAILABLE);
        }
        appendAudit(request, "TOOL_CALL_STARTED", "RUNNING", "工具调用已开始");
        try {
            ToolExecutionResult executionResult = prepared.execute();
            if (executionResult.success()) {
                appendAudit(request, "TOOL_CALL_COMPLETED", "SUCCEEDED", "工具调用完成");
                return ToolInvocationResult.succeeded(executionResult.outputSummary());
            }
            diagnosticLogger.error(diagnosticContext(request), executionResult.errorMessage());
            appendAudit(request, "TOOL_CALL_FAILED", "FAILED", "工具调用失败");
            return ToolInvocationResult.failed(TOOL_EXECUTION_FAILED);
        } catch (AuditPersistenceException auditFailure) {
            throw auditFailure;
        } catch (RuntimeException executionFailure) {
            diagnosticLogger.error(diagnosticContext(request), executionFailure);
            appendAudit(request, "TOOL_CALL_FAILED", "FAILED", "工具调用失败");
            return ToolInvocationResult.failed(TOOL_EXECUTION_FAILED);
        }
    }

    private ErrorDiagnosticLogger.DiagnosticContext diagnosticContext(ToolInvocationRequest request) {
        return new ErrorDiagnosticLogger.DiagnosticContext(
                request.toolCallId(), "GOVERNED_TOOL", TOOL_EXECUTION_FAILED,
                request.tenantId().toString(), request.principal().principalId(), request.agentId().toString(),
                request.runId().toString(), request.toolId().toString(), request.toolCallId(), "AGENT"
        );
    }

    /**
     * 判断重新读取的工具定义是否仍与调用快照一致。
     *
     * @param request 包含租户、主体、Agent、运行和工具输入的治理调用请求
     * @param definition 当前工具定义
     */
    private boolean isVisibleDefinition(ToolInvocationRequest request, ToolDefinition definition) {
        return definition.enabled()
                && request.tenantId().equals(definition.tenantId())
                && request.toolId().equals(definition.id())
                && request.toolName().equals(definition.name());
    }

    /**
     * 使用调用请求中的可信上下文追加工具调用审计事件。
     *
     * @param request 包含租户、主体、Agent、运行和工具输入的治理调用请求
     * @param eventType 工具调用审计事件类型
     * @param status 当前处理状态，用于驱动状态分支或记录结果。
     * @param message 处理结果或审计消息。
     */
    private void appendAudit(ToolInvocationRequest request, String eventType, String status, String message) {
        auditAppender.append(
                request.tenantId(), request.principal().principalId(), eventType, RESOURCE_TYPE,
                request.toolId().toString(), status, message
        );
    }
}
