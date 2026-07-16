package com.cmagent.agentscope;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationInfrastructureException;
import com.cmagent.core.runtime.ToolInvocationRequest;
import com.cmagent.core.runtime.ToolInvocationResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Hooks;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentScopeRuntimeContractTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final String INVALID_TEST_CREDENTIAL = "invalid-local-contract-key";
    private static final String SIMPLE_RESPONSE = """
            {"id":"chatcmpl-test","object":"chat.completion","created":1,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"真实运行成功"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """;
    private static final String TOOL_CALL_RESPONSE = """
            {"id":"chatcmpl-tool","object":"chat.completion","created":1,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"tool-call-1","type":"function","function":{"name":"echo","arguments":"{\\"value\\":\\"hello\\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """;
    private static final String TOOL_FINAL_RESPONSE = """
            {"id":"chatcmpl-final","object":"chat.completion","created":2,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"工具运行后的最终回答"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """;

    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void executesRealReActAgentAgainstLocalOpenAiCompatibleServer() {
        AgentRunResult result = runtime(ignored -> ToolInvocationResult.succeeded("ok"), defaultOptions())
                .run(request(List.of()));

        assertThat(result.runId()).isEqualTo(RUN_ID);
        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.output()).isEqualTo("真实运行成功");
        assertThat(result.errorMessage()).isEmpty();
        assertThat(requestCount).hasValue(1);
        assertThat(requestBodies).singleElement().asString()
                .contains("\"stream\":true")
                .contains("你好")
                .doesNotContain(INVALID_TEST_CREDENTIAL);
    }

    @Test
    void closesRealAgentAfterSuccessfulRun() {
        AtomicInteger closeCount = new AtomicInteger();
        AgentScopeReActExecutor.AgentLifecycle lifecycle = trackingLifecycle(
                new AtomicInteger(), closeCount);
        AgentRuntime runtime = runtime(
                ignored -> ToolInvocationResult.succeeded("ok"), defaultOptions(), lifecycle);

        AgentRunResult result = runtime.run(request(List.of()));

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(closeCount).hasValue(1);
    }

    @Test
    void createsFreshAgentModelToolkitAndContextForEveryRun() {
        List<ReActAgent> agents = new CopyOnWriteArrayList<>();
        List<RuntimeContext> contexts = new CopyOnWriteArrayList<>();
        AgentScopeReActExecutor.AgentLifecycle lifecycle = new AgentScopeReActExecutor.AgentLifecycle() {
            @Override
            public void onCreated(ReActAgent agent, RuntimeContext context) {
                agents.add(agent);
                contexts.add(context);
            }

            @Override
            public void interrupt(ReActAgent agent, RuntimeContext context) {
                agent.interrupt(context);
            }

            @Override
            public void close(ReActAgent agent) {
                agent.close();
            }
        };
        AgentRuntime runtime = runtime(
                ignored -> ToolInvocationResult.succeeded("ok"), defaultOptions(), lifecycle);

        AgentRunResult first = runtime.run(request(List.of()));
        AgentRunResult second = runtime.run(request(List.of()));

        assertThat(first.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(second.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(agents).hasSize(2).doesNotHaveDuplicates();
        assertThat(agents).extracting(ReActAgent::getModel).doesNotHaveDuplicates();
        assertThat(agents).extracting(ReActAgent::getToolkit).doesNotHaveDuplicates();
        assertThat(contexts).hasSize(2).doesNotHaveDuplicates();
        assertThat(contexts).allSatisfy(context -> {
            assertThat(context.getUserId()).isEqualTo(TENANT_ID + ":principal");
            assertThat(context.getSessionId()).isEqualTo(RUN_ID.toString());
            assertThat((String) context.get("tenantId")).isEqualTo(TENANT_ID.toString());
            assertThat((String) context.get("agentId")).isEqualTo(AGENT_ID.toString());
            assertThat((String) context.get("principalId")).isEqualTo("principal");
            assertThat((String) context.get("runId")).isEqualTo(RUN_ID.toString());
        });
        assertThat(requestCount).hasValue(2);
    }

    @Test
    void executesRealToolkitAndForwardsCompleteInvocationContext() {
        List<ToolInvocationRequest> invocations = new CopyOnWriteArrayList<>();
        AgentRuntime runtime = runtime(invocation -> {
            invocations.add(invocation);
            return ToolInvocationResult.succeeded("hello");
        }, defaultOptions());

        AgentRunResult result = runtime.run(request(List.of(tool())));

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.output()).isEqualTo("工具运行后的最终回答");
        assertThat(result.toolCalls()).singleElement().satisfies(record -> {
            assertThat(record.toolId()).isEqualTo(TOOL_ID);
            assertThat(record.status()).isEqualTo(RunStatus.SUCCEEDED);
        });
        assertThat(invocations).singleElement().satisfies(invocation -> {
            assertThat(invocation.tenantId()).isEqualTo(TENANT_ID);
            assertThat(invocation.agentId()).isEqualTo(AGENT_ID);
            assertThat(invocation.principal().principalId()).isEqualTo("principal");
            assertThat(invocation.runId()).isEqualTo(RUN_ID);
            assertThat(invocation.toolCallId()).isEqualTo("tool-call-1");
            assertThat(invocation.toolId()).isEqualTo(TOOL_ID);
            assertThat(invocation.toolName()).isEqualTo("echo");
            assertThat(invocation.inputJson()).isEqualTo("{\"value\":\"hello\"}");
        });
        assertThat(requestCount).hasValue(2);
    }

    @Test
    void forcesRunToDeniedWhenAnyRealToolkitRecordIsDenied() {
        AgentRuntime runtime = runtime(
                ignored -> ToolInvocationResult.denied("没有工具权限"), defaultOptions());

        AgentRunResult result = runtime.run(request(List.of(tool())));

        assertThat(result.status()).isEqualTo(RunStatus.DENIED);
        assertThat(result.toolCalls()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(RunStatus.DENIED);
            assertThat(record.authorized()).isFalse();
        });
        assertThat(requestCount).hasValue(2);
    }

    @Test
    void propagatesFatalInfrastructureFailureEvenWhenAgentScopeConsumesToolError() {
        ToolInvocationInfrastructureException failure = new ToolInvocationInfrastructureException(
                "审计写入失败", new IllegalStateException("本地测试审计存储不可用"));
        AgentRuntime runtime = runtime(invocation -> {
            throw failure;
        }, defaultOptions());

        assertThatThrownBy(() -> runtime.run(request(List.of(tool())))).isSameAs(failure);
        assertThat(requestCount).hasValue(2);
    }

    @Test
    void keepsFatalInfrastructureFailureAheadOfLaterProviderFailure() {
        ToolInvocationInfrastructureException failure = new ToolInvocationInfrastructureException(
                "审计写入失败", new IllegalStateException("本地测试审计存储不可用"));
        AgentRuntime runtime = runtime(invocation -> {
            throw failure;
        }, defaultOptions());

        assertThatThrownBy(() -> runtime.run(request(List.of(tool()), "致命失败后模型失败")))
                .isSameAs(failure);
        assertThat(requestCount).hasValue(2);
    }

    @Test
    void keepsDeniedRecordAheadOfLaterProviderFailure() {
        AgentRuntime runtime = runtime(
                ignored -> ToolInvocationResult.denied("没有工具权限"), defaultOptions());

        AgentRunResult result = runtime.run(request(List.of(tool()), "拒绝后模型失败"));

        assertThat(result.status()).isEqualTo(RunStatus.DENIED);
        assertThat(result.errorMessage()).isEqualTo("没有工具权限");
        assertThat(result.toolCalls()).singleElement().satisfies(record ->
                assertThat(record.status()).isEqualTo(RunStatus.DENIED));
        assertThat(requestCount).hasValue(2);
    }

    @Test
    void mapsRealModelTimeoutToFixedChineseMessage() {
        AgentScopeRuntimeOptions timeoutOptions =
                new AgentScopeRuntimeOptions(Duration.ofMillis(50), Duration.ofSeconds(1), 1);

        AgentRunResult result;
        Hooks.onErrorDropped(ignored -> { });
        try {
            result = runtime(ignored -> ToolInvocationResult.succeeded("ok"), timeoutOptions)
                    .run(request(List.of(), "触发超时"));
        } finally {
            Hooks.resetOnErrorDropped();
        }

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.output()).isEmpty();
        assertThat(result.errorMessage()).isEqualTo("Agent 运行超时");
    }

    @Test
    void interruptsAndClosesWhenTimeoutFollowsDeniedRecord() {
        AtomicInteger interruptCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        AgentScopeRuntimeOptions timeoutOptions =
                new AgentScopeRuntimeOptions(Duration.ofSeconds(1), Duration.ofSeconds(1), 1);
        AgentRuntime runtime = runtime(
                ignored -> ToolInvocationResult.denied("没有工具权限"),
                timeoutOptions,
                trackingLifecycle(interruptCount, closeCount));

        AgentRunResult result;
        Hooks.onErrorDropped(ignored -> { });
        try {
            result = runtime.run(request(List.of(tool()), "拒绝后超时"));
        } finally {
            Hooks.resetOnErrorDropped();
        }

        assertThat(requestCount).hasValue(2);
        assertThat(result.toolCalls()).singleElement().satisfies(record ->
                assertThat(record.status()).isEqualTo(RunStatus.DENIED));
        assertThat(result.status()).isEqualTo(RunStatus.DENIED);
        assertThat(interruptCount).hasValue(1);
        assertThat(closeCount).hasValue(1);
    }

    @Test
    void mapsRealProviderHttpFailureToFixedChineseMessage() {
        AgentRunResult result = runtime(ignored -> ToolInvocationResult.succeeded("ok"), defaultOptions())
                .run(request(List.of(), "触发模型失败"));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.output()).isEmpty();
        assertThat(result.errorMessage())
                .isEqualTo("Agent 运行失败")
                .doesNotContain("本地模型服务失败");
        assertThat(requestCount).hasValue(1);
    }

    private AgentRuntime runtime(
            ToolInvocationGateway gateway,
            AgentScopeRuntimeOptions options
    ) {
        return AgentScopeRuntimeAdapter.create(
                (tenantId, modelConfigId) -> new ModelCredential(INVALID_TEST_CREDENTIAL),
                gateway,
                options,
                Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC));
    }

    private AgentRuntime runtime(
            ToolInvocationGateway gateway,
            AgentScopeRuntimeOptions options,
            AgentScopeReActExecutor.AgentLifecycle lifecycle
    ) {
        return new AgentScopeRuntimeAdapter(
                (tenantId, modelConfigId) -> new ModelCredential(INVALID_TEST_CREDENTIAL),
                gateway,
                new AgentScopeReActExecutor(options, new AgentScopeModelFactory(), lifecycle),
                Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC));
    }

    private static AgentScopeReActExecutor.AgentLifecycle trackingLifecycle(
            AtomicInteger interruptCount,
            AtomicInteger closeCount
    ) {
        return new AgentScopeReActExecutor.AgentLifecycle() {
            @Override
            public void interrupt(ReActAgent agent, RuntimeContext context) {
                interruptCount.incrementAndGet();
                agent.interrupt(context);
            }

            @Override
            public void close(ReActAgent agent) {
                closeCount.incrementAndGet();
                agent.close();
            }
        };
    }

    private void respond(HttpExchange exchange) throws IOException {
        int current = requestCount.incrementAndGet();
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (Thread.currentThread().isInterrupted()) {
            exchange.close();
            return;
        }
        if (isTimeoutTestRequest()) {
            try {
                Thread.sleep(requestBodies.getFirst().contains("拒绝后超时") ? 1_500 : 300);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
        }
        if (shouldFailModelRequest(current)) {
            respondWithProviderFailure(exchange);
            return;
        }
        String response = requestBodies.getFirst().contains("\"tools\"")
                ? (current == 1 ? TOOL_CALL_RESPONSE : TOOL_FINAL_RESPONSE)
                : SIMPLE_RESPONSE;
        byte[] body = ("data: " + response.strip() + "\n\ndata: [DONE]\n\n")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private boolean isTimeoutTestRequest() {
        String firstRequest = requestBodies.getFirst();
        return firstRequest.contains("触发超时")
                || requestCount.get() > 1 && firstRequest.contains("拒绝后超时");
    }

    private boolean shouldFailModelRequest(int currentRequest) {
        String firstRequest = requestBodies.getFirst();
        return firstRequest.contains("触发模型失败")
                || (currentRequest > 1
                && (firstRequest.contains("致命失败后模型失败")
                || firstRequest.contains("拒绝后模型失败")));
    }

    private static void respondWithProviderFailure(HttpExchange exchange) throws IOException {
        byte[] body = "{\"error\":{\"message\":\"本地模型服务失败\"}}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(500, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private AgentRunRequest request(List<ToolDefinition> tools) {
        return request(tools, "你好");
    }

    private AgentRunRequest request(List<ToolDefinition> tools, String input) {
        List<UUID> toolIds = tools.stream().map(ToolDefinition::id).toList();
        AgentDefinition agent = new AgentDefinition(
                AGENT_ID, TENANT_ID, "企业助手", "", "你是企业助手", MODEL_ID,
                "test-model", 0.2, 5, true, toolIds, "tester", "tester");
        ModelConfig model = new ModelConfig(
                MODEL_ID, TENANT_ID, ModelProviderType.OPENAI_COMPATIBLE,
                "测试模型", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-model", true);
        PrincipalRef principal = new PrincipalRef(
                TENANT_ID, "principal", "测试主体", Set.of("agent:run"));
        return new AgentRunRequest(RUN_ID, TENANT_ID, agent, model, principal, input, tools);
    }

    private static ToolDefinition tool() {
        return new ToolDefinition(
                TOOL_ID, TENANT_ID, "echo", "回显输入", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}",
                ToolRiskLevel.LOW, true, "", "tester", "tester");
    }

    private static AgentScopeRuntimeOptions defaultOptions() {
        return new AgentScopeRuntimeOptions(Duration.ofSeconds(2), Duration.ofSeconds(1), 1);
    }
}
