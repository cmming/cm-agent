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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;

class AgentScopeRuntimeContractTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID TENANT_ID_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID AGENT_ID_B = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID MODEL_ID_B = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final UUID TOOL_ID_B = UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final UUID RUN_ID_B = UUID.fromString("00000000-0000-0000-0000-000000000602");
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
    private static final String PARALLEL_TOOL_CALL_RESPONSE = """
            {"id":"chatcmpl-parallel","object":"chat.completion","created":1,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"tool-call-1","type":"function","function":{"name":"echo","arguments":"{\\"value\\":\\"first\\"}"}},{"id":"tool-call-2","type":"function","function":{"name":"echo","arguments":"{\\"value\\":\\"second\\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """;

    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<Boolean> requestHeadersValid = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService serverExecutor;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newFixedThreadPool(4);
        server.setExecutor(serverExecutor);
        server.createContext("/v1/chat/completions", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
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
        assertThat(result.output()).doesNotContain(INVALID_TEST_CREDENTIAL);
        assertThat(result.errorMessage()).doesNotContain(INVALID_TEST_CREDENTIAL);
        assertThat(requestCount).hasValue(1);
        assertThat(requestHeadersValid).containsExactly(true);
        assertThat(requestBodies).singleElement().asString()
                .contains("\"stream\":true")
                .contains("你好")
                .doesNotContain(INVALID_TEST_CREDENTIAL);
    }

    @Test
    void localServerRejectsUnexpectedMethodAndSubpath() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1/chat/completions";
        HttpResponse<Void> getResponse = client.send(
                HttpRequest.newBuilder(URI.create(endpoint)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        HttpResponse<Void> subpathResponse = client.send(
                HttpRequest.newBuilder(URI.create(endpoint + "/extra"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(getResponse.statusCode()).isEqualTo(405);
        assertThat(subpathResponse.statusCode()).isEqualTo(404);
        assertThat(requestCount).hasValue(0);
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
    void isolatesConcurrentRunsOnTheSameRuntime() {
        List<ToolInvocationRequest> invocations = new CopyOnWriteArrayList<>();
        List<RuntimeContext> contexts = new CopyOnWriteArrayList<>();
        CountDownLatch bothGatewaysEntered = new CountDownLatch(2);
        AgentScopeReActExecutor.AgentLifecycle lifecycle = new AgentScopeReActExecutor.AgentLifecycle() {
            @Override
            public void onCreated(ReActAgent agent, RuntimeContext context) {
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
        AgentRuntime runtime = runtime(invocation -> {
            invocations.add(invocation);
            bothGatewaysEntered.countDown();
            try {
                if (!bothGatewaysEntered.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发网关未同时进入");
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并发网关等待被中断", interruptedException);
            }
            return ToolInvocationResult.succeeded(invocation.principal().principalId());
        }, defaultOptions(), lifecycle);
        AgentRunRequest firstRequest = request(
                TENANT_ID, AGENT_ID, MODEL_ID, RUN_ID, "principal-a",
                List.of(tool(TOOL_ID, TENANT_ID)), "并发请求甲");
        AgentRunRequest secondRequest = request(
                TENANT_ID_B, AGENT_ID_B, MODEL_ID_B, RUN_ID_B, "principal-b",
                List.of(tool(TOOL_ID_B, TENANT_ID_B)), "并发请求乙");

        CompletableFuture<AgentRunResult> firstFuture =
                CompletableFuture.supplyAsync(() -> runtime.run(firstRequest));
        CompletableFuture<AgentRunResult> secondFuture =
                CompletableFuture.supplyAsync(() -> runtime.run(secondRequest));
        AgentRunResult first = firstFuture.join();
        AgentRunResult second = secondFuture.join();

        assertThat(first.runId()).isEqualTo(RUN_ID);
        assertThat(first.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(first.output()).isEqualTo("并发回答甲");
        assertThat(first.toolCalls()).singleElement().satisfies(record ->
                assertThat(record.toolId()).isEqualTo(TOOL_ID));
        assertThat(second.runId()).isEqualTo(RUN_ID_B);
        assertThat(second.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(second.output()).isEqualTo("并发回答乙");
        assertThat(second.toolCalls()).singleElement().satisfies(record ->
                assertThat(record.toolId()).isEqualTo(TOOL_ID_B));
        assertThat(invocations).hasSize(2).allSatisfy(invocation -> {
            if (invocation.runId().equals(RUN_ID)) {
                assertThat(invocation.tenantId()).isEqualTo(TENANT_ID);
                assertThat(invocation.agentId()).isEqualTo(AGENT_ID);
                assertThat(invocation.principal().principalId()).isEqualTo("principal-a");
                assertThat(invocation.toolId()).isEqualTo(TOOL_ID);
            } else {
                assertThat(invocation.runId()).isEqualTo(RUN_ID_B);
                assertThat(invocation.tenantId()).isEqualTo(TENANT_ID_B);
                assertThat(invocation.agentId()).isEqualTo(AGENT_ID_B);
                assertThat(invocation.principal().principalId()).isEqualTo("principal-b");
                assertThat(invocation.toolId()).isEqualTo(TOOL_ID_B);
            }
        });
        assertThat(contexts).hasSize(2).allSatisfy(context -> {
            String runId = (String) context.get("runId");
            if (RUN_ID.toString().equals(runId)) {
                assertThat((String) context.get("tenantId")).isEqualTo(TENANT_ID.toString());
                assertThat((String) context.get("agentId")).isEqualTo(AGENT_ID.toString());
                assertThat((String) context.get("principalId")).isEqualTo("principal-a");
            } else {
                assertThat(runId).isEqualTo(RUN_ID_B.toString());
                assertThat((String) context.get("tenantId")).isEqualTo(TENANT_ID_B.toString());
                assertThat((String) context.get("agentId")).isEqualTo(AGENT_ID_B.toString());
                assertThat((String) context.get("principalId")).isEqualTo("principal-b");
            }
        });
        assertThat(requestCount).hasValue(4);
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
        AtomicInteger gatewayCount = new AtomicInteger();
        AgentRuntime runtime = runtime(invocation -> {
            gatewayCount.incrementAndGet();
            throw failure;
        }, defaultOptions());

        assertThatThrownBy(() -> runtime.run(request(List.of(tool())))).isSameAs(failure);
        assertThat(requestCount).hasValue(1);
        assertThat(gatewayCount).hasValue(1);
    }

    @Test
    void abortsParallelAndLaterToolCallsImmediatelyAfterFirstFatalFailure() {
        ToolInvocationInfrastructureException failure = new ToolInvocationInfrastructureException(
                "审计写入失败", new IllegalStateException("本地测试审计存储不可用"));
        AtomicInteger gatewayCount = new AtomicInteger();
        AgentRuntime runtime = runtime(invocation -> {
            gatewayCount.incrementAndGet();
            throw failure;
        }, defaultOptions());

        assertThatThrownBy(() -> runtime.run(request(List.of(tool()), "致命失败后再次调用工具")))
                .isSameAs(failure);
        assertThat(requestCount).hasValue(1);
        assertThat(gatewayCount).hasValue(1);
    }

    @Test
    void preservesFatalFailureWhenClosingAgentAlsoFails() {
        ToolInvocationInfrastructureException failure = new ToolInvocationInfrastructureException(
                "审计写入失败", new IllegalStateException("本地测试审计存储不可用"));
        IllegalStateException closeFailure = new IllegalStateException("本地测试关闭失败");
        AgentScopeReActExecutor.AgentLifecycle lifecycle =
                new AgentScopeReActExecutor.AgentLifecycle() {
                    @Override
                    public void interrupt(ReActAgent agent, RuntimeContext context) {
                        agent.interrupt(context);
                    }

                    @Override
                    public void close(ReActAgent agent) {
                        throw closeFailure;
                    }
                };
        AgentRuntime runtime = runtime(invocation -> {
            throw failure;
        }, defaultOptions(), lifecycle);

        Throwable thrown = catchThrowable(() -> runtime.run(request(List.of(tool()))));

        assertThat(thrown).isSameAs(failure);
        assertThat(thrown.getSuppressed()).contains(closeFailure);
    }

    @Test
    void preservesFatalFailureWhenInterruptingAndClosingAgentBothFail() {
        ToolInvocationInfrastructureException failure = new ToolInvocationInfrastructureException(
                "审计写入失败", new IllegalStateException("本地测试审计存储不可用"));
        IllegalStateException interruptFailure = new IllegalStateException("本地测试中止失败");
        IllegalStateException closeFailure = new IllegalStateException("本地测试关闭失败");
        AgentScopeReActExecutor.AgentLifecycle lifecycle =
                new AgentScopeReActExecutor.AgentLifecycle() {
                    @Override
                    public void interrupt(ReActAgent agent, RuntimeContext context) {
                        throw interruptFailure;
                    }

                    @Override
                    public void close(ReActAgent agent) {
                        throw closeFailure;
                    }
                };
        AgentRuntime runtime = runtime(invocation -> {
            throw failure;
        }, defaultOptions(), lifecycle);

        Throwable thrown = catchThrowable(() -> runtime.run(request(List.of(tool()))));

        assertThat(thrown).isSameAs(failure);
        assertThat(thrown.getSuppressed()).contains(interruptFailure, closeFailure);
    }

    @Test
    void propagatesCloseFailureInsteadOfDiscardingItAfterControlledProviderFailure() {
        IllegalStateException closeFailure = new IllegalStateException("本地测试关闭失败");
        AgentScopeReActExecutor.AgentLifecycle lifecycle =
                new AgentScopeReActExecutor.AgentLifecycle() {
                    @Override
                    public void interrupt(ReActAgent agent, RuntimeContext context) {
                        agent.interrupt(context);
                    }

                    @Override
                    public void close(ReActAgent agent) {
                        throw closeFailure;
                    }
                };
        AgentRuntime runtime = runtime(
                ignored -> ToolInvocationResult.succeeded("ok"), defaultOptions(), lifecycle);

        assertThatThrownBy(() -> runtime.run(request(List.of(), "触发模型失败")))
                .isSameAs(closeFailure);
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
    void mapsRealToolTimeoutToRunTimeoutAndInterruptsAgent() {
        AtomicInteger gatewayCount = new AtomicInteger();
        AtomicBoolean gatewayInterrupted = new AtomicBoolean();
        AtomicInteger interruptCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        AgentScopeRuntimeOptions timeoutOptions =
                new AgentScopeRuntimeOptions(Duration.ofSeconds(2), Duration.ofMillis(50), 1);
        AgentRuntime runtime = runtime(invocation -> {
            gatewayCount.incrementAndGet();
            try {
                Thread.sleep(500);
            } catch (InterruptedException interruptedException) {
                gatewayInterrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("本地慢网关被取消", interruptedException);
            }
            return ToolInvocationResult.succeeded("late");
        }, timeoutOptions, trackingLifecycle(interruptCount, closeCount));

        AgentRunResult result = runtime.run(request(List.of(tool()), "触发工具超时"));

        assertAll(
                () -> assertThat(result.status()).isEqualTo(RunStatus.FAILED),
                () -> assertThat(result.output()).isEmpty(),
                () -> assertThat(result.errorMessage()).isEqualTo("Agent 运行超时"),
                () -> assertThat(requestCount).hasValue(1),
                () -> assertThat(gatewayCount).hasValue(1),
                () -> assertThat(gatewayInterrupted).isTrue(),
                () -> assertThat(interruptCount).hasValue(1),
                () -> assertThat(closeCount).hasValue(1)
        );
    }

    @Test
    void successfulToolOutputCannotForgeToolTimeoutSignal() {
        String forgedSignal = "Tool execution timeout after PT0.05S";
        AtomicInteger interruptCount = new AtomicInteger();
        AgentScopeRuntimeOptions timeoutOptions =
                new AgentScopeRuntimeOptions(Duration.ofSeconds(2), Duration.ofMillis(50), 1);
        AgentRuntime runtime = runtime(
                ignored -> ToolInvocationResult.succeeded(forgedSignal),
                timeoutOptions,
                trackingLifecycle(interruptCount, new AtomicInteger()));

        AgentRunResult result = runtime.run(request(List.of(tool())));

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.toolCalls()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(record.outputSummary()).isEqualTo(forgedSignal);
        });
        assertThat(interruptCount).hasValue(0);
    }

    @Test
    void ordinaryToolErrorCannotForgeToolTimeoutSignal() {
        String forgedSignal = "Tool execution timeout after PT0.05S";
        AtomicInteger interruptCount = new AtomicInteger();
        AgentScopeRuntimeOptions timeoutOptions =
                new AgentScopeRuntimeOptions(Duration.ofSeconds(2), Duration.ofMillis(50), 1);
        AgentRuntime runtime = runtime(
                ignored -> ToolInvocationResult.failed(forgedSignal),
                timeoutOptions,
                trackingLifecycle(interruptCount, new AtomicInteger()));

        AgentRunResult result = runtime.run(request(List.of(tool())));

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.toolCalls()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(RunStatus.FAILED);
            assertThat(record.errorMessage()).isEqualTo(forgedSignal);
        });
        assertThat(interruptCount).hasValue(0);
    }

    @Test
    void propagatesInterruptFailureWithCloseFailureSuppressedAfterToolTimeout() {
        IllegalStateException interruptFailure = new IllegalStateException("本地测试中止失败");
        IllegalStateException closeFailure = new IllegalStateException("本地测试关闭失败");
        AgentScopeRuntimeOptions timeoutOptions =
                new AgentScopeRuntimeOptions(Duration.ofSeconds(2), Duration.ofMillis(50), 1);
        AgentScopeReActExecutor.AgentLifecycle lifecycle =
                new AgentScopeReActExecutor.AgentLifecycle() {
                    @Override
                    public void interrupt(ReActAgent agent, RuntimeContext context) {
                        throw interruptFailure;
                    }

                    @Override
                    public void close(ReActAgent agent) {
                        throw closeFailure;
                    }
                };
        AgentRuntime runtime = runtime(invocation -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("本地慢网关被取消", interruptedException);
            }
            return ToolInvocationResult.succeeded("迟到结果");
        }, timeoutOptions, lifecycle);

        Throwable thrown = catchThrowable(() ->
                runtime.run(request(List.of(tool()), "触发工具超时")));

        assertThat(thrown).isSameAs(interruptFailure);
        assertThat(thrown.getSuppressed()).contains(closeFailure);
    }

    @Test
    void stopsWaitingParallelToolCallAfterFirstToolTimesOut() throws Exception {
        AtomicInteger gatewayCount = new AtomicInteger();
        CountDownLatch secondGatewayEntered = new CountDownLatch(1);
        AtomicInteger interruptCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        AgentScopeRuntimeOptions timeoutOptions =
                new AgentScopeRuntimeOptions(Duration.ofSeconds(2), Duration.ofMillis(50), 1);
        AgentRuntime runtime = runtime(invocation -> {
            int current = gatewayCount.incrementAndGet();
            if (current > 1) {
                secondGatewayEntered.countDown();
                return ToolInvocationResult.succeeded("不应执行");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("本地并行慢网关被取消", interruptedException);
            }
            return ToolInvocationResult.succeeded("late");
        }, timeoutOptions, trackingLifecycle(interruptCount, closeCount));

        AgentRunResult result = runtime.run(request(List.of(tool()), "并行工具超时"));
        boolean laterGatewayExecuted = secondGatewayEntered.await(300, TimeUnit.MILLISECONDS);

        assertAll(
                () -> assertThat(result.status()).isEqualTo(RunStatus.FAILED),
                () -> assertThat(result.errorMessage()).isEqualTo("Agent 运行超时"),
                () -> assertThat(requestCount).hasValue(1),
                () -> assertThat(gatewayCount).hasValue(1),
                () -> assertThat(laterGatewayExecuted).isFalse(),
                () -> assertThat(interruptCount).hasValue(1),
                () -> assertThat(closeCount).hasValue(1)
        );
    }

    @Test
    void returnsBeforeLateGatewayAndStopsInterruptedParallelWaiter() throws Exception {
        AtomicInteger gatewayCount = new AtomicInteger();
        CountDownLatch firstGatewayEntered = new CountDownLatch(1);
        CountDownLatch releaseLateGateway = new CountDownLatch(1);
        CountDownLatch lateGatewayFinished = new CountDownLatch(1);
        CountDownLatch secondGatewayEntered = new CountDownLatch(1);
        AtomicInteger interruptCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        AgentScopeRuntimeOptions timeoutOptions =
                new AgentScopeRuntimeOptions(Duration.ofSeconds(2), Duration.ofMillis(50), 1);
        AgentRuntime runtime = runtime(invocation -> {
            int current = gatewayCount.incrementAndGet();
            if (current > 1) {
                secondGatewayEntered.countDown();
                return ToolInvocationResult.succeeded("不应执行");
            }
            firstGatewayEntered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = releaseLateGateway.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // 本合同刻意模拟忽略中断的外部工具。
                }
            }
            lateGatewayFinished.countDown();
            return ToolInvocationResult.succeeded("迟到结果");
        }, timeoutOptions, trackingLifecycle(interruptCount, closeCount));
        CompletableFuture<AgentRunResult> future = CompletableFuture.supplyAsync(() ->
                runtime.run(request(List.of(tool()), "迟到工具超时")));

        AgentRunResult result;
        try {
            assertThat(firstGatewayEntered.await(3, TimeUnit.SECONDS)).isTrue();
            result = future.get(1, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(RunStatus.FAILED);
            assertThat(result.errorMessage()).isEqualTo("Agent 运行超时");
            assertThat(result.toolCalls()).isEmpty();
            assertThat(gatewayCount).hasValue(1);
            assertThat(interruptCount).hasValue(1);
            assertThat(closeCount).hasValue(1);
        } finally {
            releaseLateGateway.countDown();
        }
        assertThat(lateGatewayFinished.await(1, TimeUnit.SECONDS)).isTrue();
        boolean laterGatewayExecuted = secondGatewayEntered.await(300, TimeUnit.MILLISECONDS);
        assertThat(laterGatewayExecuted).isFalse();
        assertThat(gatewayCount).hasValue(1);
        assertThat(result.toolCalls()).isEmpty();
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
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondWithoutBody(exchange, 405);
            return;
        }
        if (!"/v1/chat/completions".equals(exchange.getRequestURI().getPath())) {
            respondWithoutBody(exchange, 404);
            return;
        }
        String requestBody = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int current = requestCount.incrementAndGet();
        requestBodies.add(requestBody);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        requestHeadersValid.add(contentType != null
                && contentType.startsWith("application/json")
                && ("Bearer " + INVALID_TEST_CREDENTIAL).equals(authorization));
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
        String response = responseFor(current, requestBody);
        byte[] body = ("data: " + response.strip() + "\n\ndata: [DONE]\n\n")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private String responseFor(int current, String requestBody) {
        if (requestBody.contains("并行工具超时")) {
            return requestBody.contains("\"role\":\"tool\"")
                    ? TOOL_FINAL_RESPONSE
                    : PARALLEL_TOOL_CALL_RESPONSE;
        }
        if (requestBody.contains("迟到工具超时")) {
            return requestBody.contains("\"role\":\"tool\"")
                    ? TOOL_FINAL_RESPONSE
                    : PARALLEL_TOOL_CALL_RESPONSE;
        }
        if (requestBody.contains("并发请求甲")) {
            return requestBody.contains("\"role\":\"tool\"")
                    ? TOOL_FINAL_RESPONSE.replace("工具运行后的最终回答", "并发回答甲")
                    : TOOL_CALL_RESPONSE.replace("tool-call-1", "tool-call-a");
        }
        if (requestBody.contains("并发请求乙")) {
            return requestBody.contains("\"role\":\"tool\"")
                    ? TOOL_FINAL_RESPONSE.replace("工具运行后的最终回答", "并发回答乙")
                    : TOOL_CALL_RESPONSE.replace("tool-call-1", "tool-call-b");
        }
        return requestBodies.getFirst().contains("致命失败后再次调用工具")
                ? (current <= 2 ? PARALLEL_TOOL_CALL_RESPONSE : TOOL_FINAL_RESPONSE)
                : requestBodies.getFirst().contains("\"tools\"")
                ? (current == 1 ? TOOL_CALL_RESPONSE : TOOL_FINAL_RESPONSE)
                : SIMPLE_RESPONSE;
    }

    private static void respondWithoutBody(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
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
                && firstRequest.contains("拒绝后模型失败"));
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
        return request(
                TENANT_ID, AGENT_ID, MODEL_ID, RUN_ID, "principal", tools, input);
    }

    private AgentRunRequest request(
            UUID tenantId,
            UUID agentId,
            UUID modelId,
            UUID runId,
            String principalId,
            List<ToolDefinition> tools,
            String input
    ) {
        List<UUID> toolIds = tools.stream().map(ToolDefinition::id).toList();
        AgentDefinition agent = new AgentDefinition(
                agentId, tenantId, "企业助手", "", "你是企业助手", modelId,
                "test-model", 0.2, 5, true, toolIds, "tester", "tester");
        ModelConfig model = new ModelConfig(
                modelId, tenantId, ModelProviderType.OPENAI_COMPATIBLE,
                "测试模型", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-model", true);
        PrincipalRef principal = new PrincipalRef(
                tenantId, principalId, "测试主体", Set.of("agent:run"));
        return new AgentRunRequest(runId, tenantId, agent, model, principal, input, tools);
    }

    private static ToolDefinition tool() {
        return tool(TOOL_ID, TENANT_ID);
    }

    private static ToolDefinition tool(UUID toolId, UUID tenantId) {
        return new ToolDefinition(
                toolId, tenantId, "echo", "回显输入", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}",
                ToolRiskLevel.LOW, true, "", "tester", "tester");
    }

    private static AgentScopeRuntimeOptions defaultOptions() {
        return new AgentScopeRuntimeOptions(Duration.ofSeconds(2), Duration.ofSeconds(1), 1);
    }
}
