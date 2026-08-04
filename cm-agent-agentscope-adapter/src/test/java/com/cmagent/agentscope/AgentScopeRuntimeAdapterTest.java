package com.cmagent.agentscope;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolCallRecord;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ModelCredentialProvider;
import com.cmagent.core.runtime.ModelCredentialUnavailableException;
import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentScopeRuntimeAdapterTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final Instant FIXED_TIME = Instant.parse("2026-07-16T00:00:00Z");

    @Test
    /**
     * 验证 {@code CompleteRequestToRunSpec} 的映射结果。
     */
    void mapsCompleteRequestToRunSpec() {
        AgentScopeRuntimeAdapter adapter = adapter((spec, credential, gateway) ->
                AgentScopeExecutionResult.succeeded("真实回答", List.of()));
        AgentRunRequest request = request();

        AgentScopeRunSpec spec = adapter.toRunSpec(request);

        assertThat(spec.request()).isSameAs(request);
        assertThat(spec.runId()).isEqualTo(RUN_ID);
        assertThat(spec.tenantId()).isEqualTo(TENANT_ID);
        assertThat(spec.agentId()).isEqualTo(AGENT_ID);
        assertThat(spec.userInput()).isEqualTo("你好");
        assertThat(spec.principalId()).isEqualTo("principal");
    }

    @Test
    /**
     * 验证 {@code SuccessfulExecutionToCoreResult} 的映射结果。
     */
    void mapsSuccessfulExecutionToCoreResult() {
        AgentRuntime runtime = adapter((spec, credential, gateway) ->
                AgentScopeExecutionResult.succeeded("真实回答", List.of()));

        AgentRunResult result = runtime.run(request());

        assertThat(result.runId()).isEqualTo(RUN_ID);
        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.output()).isEqualTo("真实回答");
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.startedAt()).isEqualTo(FIXED_TIME);
        assertThat(result.finishedAt()).isEqualTo(FIXED_TIME);
        assertThat(result.errorMessage()).isEmpty();
    }

    @Test
    /**
     * 验证处理过程会保留 {@code DeniedExecutionAsDeniedRun}。
     */
    void preservesDeniedExecutionAsDeniedRun() {
        AgentRuntime runtime = adapter((spec, credential, gateway) ->
                AgentScopeExecutionResult.denied("没有工具权限", List.of()));

        AgentRunResult result = runtime.run(request());

        assertThat(result.status()).isEqualTo(RunStatus.DENIED);
        assertThat(result.output()).isEmpty();
        assertThat(result.errorMessage()).isEqualTo("没有工具权限");
    }

    @Test
    /**
     * 验证处理过程会保留 {@code ControlledTimeoutMessage}。
     */
    void preservesControlledTimeoutMessage() {
        AgentRuntime runtime = adapter((spec, credential, gateway) ->
                AgentScopeExecutionResult.failed("Agent 运行超时", List.of()));

        AgentRunResult result = runtime.run(request());

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.output()).isEmpty();
        assertThat(result.errorMessage()).isEqualTo("Agent 运行超时");
    }

    @Test
    /**
     * 验证 {@code UnavailableCredentialWithoutLeakingCauseOrCredential} 的映射结果。
     */
    void mapsUnavailableCredentialWithoutLeakingCauseOrCredential() {
        String sensitiveValue = "credential-sensitive-value";
        ModelCredentialProvider provider = (tenantId, modelConfigId) -> {
            throw new ModelCredentialUnavailableException(new IllegalStateException(sensitiveValue));
        };
        AgentRuntime runtime = new AgentScopeRuntimeAdapter(
                provider,
                ignored -> ToolInvocationResult.succeeded("ok"),
                (spec, credential, gateway) -> AgentScopeExecutionResult.succeeded("不应执行", List.of()),
                fixedClock());

        AgentRunResult result = runtime.run(request());

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.output()).isEmpty();
        assertThat(result.errorMessage()).isEqualTo("模型凭据不可用").doesNotContain(sensitiveValue);
    }

    @Test
    /**
     * 验证异常传播会保留 {@code UnexpectedExecutorFailure}。
     */
    void propagatesUnexpectedExecutorFailure() {
        IllegalStateException failure = new IllegalStateException("未知执行失败");
        AgentRuntime runtime = adapter((spec, credential, gateway) -> {
            throw failure;
        });

        assertThatThrownBy(() -> runtime.run(request())).isSameAs(failure);
    }

    @Test
    /**
     * 验证 {@code executionResultRejectsRunningStatus} 所描述的业务行为。
     */
    void executionResultRejectsRunningStatus() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AgentScopeExecutionResult(
                        RunStatus.RUNNING, "", List.of(), ""))
                .withMessage("执行结果必须是终态");
    }

    @Test
    /**
     * 验证 {@code completedResultPrefersDeniedRecordWhenFinalMessageIsMissing} 所描述的业务行为。
     */
    void completedResultPrefersDeniedRecordWhenFinalMessageIsMissing() {
        ToolCallRecord denied = new ToolCallRecord(
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                "echo", "输入字段: [value]", "", RunStatus.DENIED,
                Duration.ZERO, false, "没有工具权限");

        AgentScopeExecutionResult result =
                AgentScopeReActExecutor.completedResult(null, List.of(denied));

        assertThat(result.status()).isEqualTo(RunStatus.DENIED);
        assertThat(result.output()).isEmpty();
        assertThat(result.errorMessage()).isEqualTo("没有工具权限");
    }

    /**
     * 验证 {@code adapter} 所描述的业务行为。
     *
     * @param executor 测试执行器
     */
    private static AgentScopeRuntimeAdapter adapter(AgentScopeExecutor executor) {
        ModelCredentialProvider credentialProvider =
                (tenantId, modelConfigId) -> new ModelCredential("unit-test-key");
        ToolInvocationGateway toolGateway = ignored -> ToolInvocationResult.succeeded("ok");
        return new AgentScopeRuntimeAdapter(credentialProvider, toolGateway, executor, fixedClock());
    }

    /**
     * 创建时间固定的测试时钟。
     */
    private static Clock fixedClock() {
        return Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
    }

    /**
     * 构造测试使用的运行或 HTTP 请求。
     */
    private static AgentRunRequest request() {
        AgentDefinition agent = new AgentDefinition(
                AGENT_ID, TENANT_ID, "企业助手", "", "你是企业助手", MODEL_ID,
                "test-model", 0.2, 5, true, List.of(), "tester", "tester");
        ModelConfig model = new ModelConfig(
                MODEL_ID, TENANT_ID, ModelProviderType.OPENAI_COMPATIBLE,
                "测试模型", "http://127.0.0.1:1/v1", "test-model", true);
        PrincipalRef principal = new PrincipalRef(
                TENANT_ID, "principal", "测试主体", Set.of("agent:run"));
        return new AgentRunRequest(RUN_ID, TENANT_ID, agent, model, principal, "你好", List.of());
    }
}
