package com.cmagent.agentscope;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.core.runtime.ToolInvocationInfrastructureException;
import com.cmagent.core.runtime.ToolInvocationRequest;
import com.cmagent.core.runtime.ToolInvocationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentScopeToolBridgeTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesValidObjectJsonSchema() {
        AgentScopeToolBridge bridge = bridge(request(), tool(validSchema()), ignored -> ToolInvocationResult.succeeded("ok"));

        assertThat(bridge.getName()).isEqualTo("echo");
        assertThat(bridge.getDescription()).isEqualTo("回显输入");
        assertThat(bridge.getParameters())
                .containsEntry("type", "object")
                .containsKey("properties");
    }

    @Test
    void acceptsValidObjectJsonSchemaWithNullKeywordValue() {
        AgentScopeToolBridge bridge = bridge(
                request(),
                tool("{\"type\":\"object\",\"properties\":{},\"default\":null}"),
                ignored -> ToolInvocationResult.succeeded("ok"));

        assertThat(bridge.getParameters()).containsKey("default");
        assertThat(bridge.getParameters().get("default")).isNull();
    }

    @Test
    void rejectsNonObjectJsonSchema() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> bridge(request(), tool("{\"type\":\"array\",\"items\":{}}"),
                        ignored -> ToolInvocationResult.succeeded("ok")))
                .withMessage("工具输入 Schema 必须是 object");
    }

    @Test
    void rejectsMalformedJsonSchemaWithoutLeakingParserDetails() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> bridge(request(), tool("{not-json}"),
                        ignored -> ToolInvocationResult.succeeded("ok")))
                .withMessage("工具输入 Schema 不是合法 JSON");
    }

    @Test
    void routesSuccessfulCallThroughGatewayWithCompleteContext() {
        List<ToolInvocationRequest> invocations = new CopyOnWriteArrayList<>();
        ToolInvocationGateway gateway = invocation -> {
            invocations.add(invocation);
            return ToolInvocationResult.succeeded("hello");
        };
        AgentScopeToolBridge bridge = bridge(request(), tool(validSchema()), gateway);

        ToolResultBlock block = bridge.callAsync(toolCallParam()).block();

        assertThat(block).isNotNull();
        assertThat(block.getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(outputText(block)).isEqualTo("hello");
        assertThat(invocations).singleElement().satisfies(invocation -> {
            assertThat(invocation.tenantId()).isEqualTo(TENANT_ID);
            assertThat(invocation.agentId()).isEqualTo(AGENT_ID);
            assertThat(invocation.principal().principalId()).isEqualTo("admin");
            assertThat(invocation.runId()).isEqualTo(RUN_ID);
            assertThat(invocation.toolCallId()).isEqualTo("tool-call-1");
            assertThat(invocation.toolId()).isEqualTo(TOOL_ID);
            assertThat(invocation.toolName()).isEqualTo("echo");
            assertThat(invocation.inputJson()).isEqualTo("{\"value\":\"hello\"}");
        });
        assertThat(bridge.records()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(record.authorized()).isTrue();
            assertThat(record.duration()).isGreaterThanOrEqualTo(Duration.ZERO);
            assertThat(record.inputSummary()).contains("value").doesNotContain("hello");
            assertThat(record.outputSummary()).isEqualTo("hello");
            assertThat(record.errorMessage()).isEmpty();
        });
    }

    @Test
    void forwardsModelSuppliedToolNameForGatewayConsistencyGovernance() {
        List<ToolInvocationRequest> invocations = new CopyOnWriteArrayList<>();
        AgentScopeToolBridge bridge = bridge(request(), tool(validSchema()), invocation -> {
            invocations.add(invocation);
            return ToolInvocationResult.failed("工具不可用");
        });

        ToolResultBlock block = bridge.callAsync(
                toolCallParam("hello", "tool-call-mismatch", "model-supplied-name")).block();

        assertThat(block).isNotNull();
        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(invocations).singleElement()
                .extracting(ToolInvocationRequest::toolName)
                .isEqualTo("model-supplied-name");
        assertThat(bridge.records()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(RunStatus.FAILED);
            assertThat(record.authorized()).isTrue();
            assertThat(record.errorMessage()).isEqualTo("工具不可用");
        });
    }

    @Test
    void mapsControlledFailureToErrorResultAndRecord() {
        AgentScopeToolBridge bridge = bridge(request(), tool(validSchema()),
                ignored -> ToolInvocationResult.failed("工具执行失败"));

        ToolResultBlock block = bridge.callAsync(toolCallParam()).block();

        assertThat(block).isNotNull();
        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(outputText(block)).isEqualTo("Error: 工具执行失败");
        assertThat(bridge.records()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(RunStatus.FAILED);
            assertThat(record.authorized()).isTrue();
            assertThat(record.errorMessage()).isEqualTo("工具执行失败");
        });
    }

    @Test
    void mapsDeniedCallToDeniedUnauthorizedRecord() {
        AgentScopeToolBridge bridge = bridge(request(), tool(validSchema()),
                ignored -> ToolInvocationResult.denied("没有工具权限"));

        ToolResultBlock block = bridge.callAsync(toolCallParam()).block();

        assertThat(block).isNotNull();
        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(outputText(block)).isEqualTo("Error: 没有工具权限");
        assertThat(bridge.records()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(RunStatus.DENIED);
            assertThat(record.authorized()).isFalse();
        });
    }

    @Test
    void recordsConcurrentCallsAndReturnsImmutableSnapshots() {
        AgentScopeToolBridge bridge = bridge(request(), tool(validSchema()),
                ignored -> ToolInvocationResult.succeeded("ok"));

        CompletableFuture<?>[] calls = IntStream.range(0, 32)
                .mapToObj(index -> CompletableFuture.runAsync(() -> bridge.callAsync(toolCallParam(index)).block()))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(calls).join();

        List<?> firstSnapshot = bridge.records();
        bridge.callAsync(toolCallParam(99)).block();
        assertThat(firstSnapshot).hasSize(32);
        assertThat(bridge.records()).hasSize(33);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> bridge.records().add(null));
    }

    @Test
    void convertsUnexpectedExceptionToControlledErrorWithoutLeakingInputOrCause() {
        String sensitiveValue = "secret-input-and-key";
        AgentScopeToolBridge bridge = bridge(request(), tool(validSchema()), ignored -> {
            throw new IllegalStateException("底层异常包含 " + sensitiveValue);
        });

        ToolResultBlock block = bridge.callAsync(toolCallParam(sensitiveValue)).block();

        assertThat(block).isNotNull();
        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(outputText(block)).isEqualTo("Error: 工具调用失败").doesNotContain(sensitiveValue);
        assertThat(bridge.records()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo(RunStatus.FAILED);
            assertThat(record.authorized()).isFalse();
            assertThat(record.inputSummary()).doesNotContain(sensitiveValue);
            assertThat(record.outputSummary()).doesNotContain(sensitiveValue);
            assertThat(record.errorMessage()).isEqualTo("工具调用失败").doesNotContain(sensitiveValue);
        });
        assertThatCode(bridge::throwIfInfrastructureFailure).doesNotThrowAnyException();
    }

    @Test
    void rethrowsInfrastructureFailureWithoutCreatingOrdinaryFailureRecord() {
        ToolInvocationInfrastructureException failure = new ToolInvocationInfrastructureException(
                "审计写入失败", new IllegalStateException("数据库不可用"));
        AgentScopeToolBridge bridge = bridge(request(), tool(validSchema()), ignored -> {
            throw failure;
        });

        assertThatThrownBy(() -> bridge.callAsync(toolCallParam()).block()).isSameAs(failure);
        assertThat(bridge.records()).isEmpty();
    }

    @Test
    void retainsInfrastructureFailureAfterReactiveCallerConsumesError() {
        ToolInvocationInfrastructureException failure = new ToolInvocationInfrastructureException(
                "审计写入失败", new IllegalStateException("数据库不可用"));
        AgentScopeToolBridge bridge = bridge(request(), tool(validSchema()), ignored -> {
            throw failure;
        });

        bridge.callAsync(toolCallParam())
                .onErrorResume(ToolInvocationInfrastructureException.class, ignored -> Mono.empty())
                .block();

        assertThatThrownBy(bridge::throwIfInfrastructureFailure).isSameAs(failure);
        assertThat(bridge.records()).isEmpty();
    }

    @Test
    void firstInfrastructureFailureStopsConcurrentLaterGatewayCalls() {
        ToolInvocationInfrastructureException first = new ToolInvocationInfrastructureException(
                "首次审计写入失败", new IllegalStateException("首次数据库不可用"));
        ToolInvocationInfrastructureException later = new ToolInvocationInfrastructureException(
                "后续审计写入失败", new IllegalStateException("后续数据库不可用"));
        AtomicInteger invocationCount = new AtomicInteger();
        AgentScopeToolBridge bridge = bridge(request(), tool(validSchema()), ignored -> {
            throw invocationCount.getAndIncrement() == 0 ? first : later;
        });
        bridge.callAsync(toolCallParam())
                .onErrorResume(ToolInvocationInfrastructureException.class, ignored -> Mono.empty())
                .block();

        CompletableFuture<?>[] calls = IntStream.range(0, 32)
                .mapToObj(index -> CompletableFuture.runAsync(() -> bridge.callAsync(toolCallParam(index))
                        .onErrorResume(ToolInvocationInfrastructureException.class, ignored -> Mono.empty())
                        .block()))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(calls).join();

        assertThatThrownBy(bridge::throwIfInfrastructureFailure).isSameAs(first);
        assertThat(invocationCount).hasValue(1);
        assertThat(bridge.records()).isEmpty();
    }

    @Test
    void interruptedWaiterNeverEntersGatewayAfterCurrentInvocationReturns() throws Exception {
        AgentScopeRunGate runGate = new AgentScopeRunGate();
        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        AtomicInteger waiterGatewayCount = new AtomicInteger();
        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> runGate.invoke(ignored -> {
                holderEntered.countDown();
                try {
                    releaseHolder.await();
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("持有者等待被中断", interruptedException);
                }
                return ToolInvocationResult.succeeded("holder");
            }, invocationRequest("holder")));
            assertThat(holderEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> waiter = executor.submit(() -> {
                waiterThread.set(Thread.currentThread());
                runGate.invoke(ignored -> {
                    waiterGatewayCount.incrementAndGet();
                    return ToolInvocationResult.succeeded("waiter");
                }, invocationRequest("waiter"));
            });
            assertThat(awaitWaiting(waiterThread, Duration.ofSeconds(1))).isTrue();

            waiter.cancel(true);
            releaseHolder.countDown();
            holder.get(1, TimeUnit.SECONDS);
            executor.shutdown();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();

            assertThat(waiterGatewayCount).hasValue(0);
        } finally {
            releaseHolder.countDown();
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void lateGatewayResultAfterTimeoutNeverCreatesToolRecord() throws Exception {
        AgentScopeRunGate runGate = new AgentScopeRunGate();
        CountDownLatch gatewayEntered = new CountDownLatch(1);
        CountDownLatch releaseGateway = new CountDownLatch(1);
        AgentScopeToolBridge bridge = new AgentScopeToolBridge(
                request(), tool(validSchema()), ignored -> {
                    gatewayEntered.countDown();
                    try {
                        releaseGateway.await();
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("迟到网关等待被中断", interruptedException);
                    }
                    return ToolInvocationResult.succeeded("迟到结果");
                }, objectMapper, runGate);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> call = executor.submit(() -> bridge.callAsync(toolCallParam()).block());
            assertThat(gatewayEntered.await(1, TimeUnit.SECONDS)).isTrue();
            runGate.markToolTimeout();
            releaseGateway.countDown();

            assertThatThrownBy(() -> call.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AgentScopeRunGate.RunAbortedException.class);
            assertThat(bridge.records()).isEmpty();
        } finally {
            releaseGateway.countDown();
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void ordinaryReactiveCancellationDoesNotMarkToolTimeout() throws Exception {
        AgentScopeRunGate runGate = new AgentScopeRunGate(Duration.ofMillis(20));
        CountDownLatch gatewayEntered = new CountDownLatch(1);
        CountDownLatch releaseGateway = new CountDownLatch(1);
        CountDownLatch gatewayReturned = new CountDownLatch(1);
        AtomicInteger gatewayCount = new AtomicInteger();
        AgentScopeToolBridge bridge = new AgentScopeToolBridge(
                request(), tool(validSchema()), ignored -> {
                    gatewayCount.incrementAndGet();
                    gatewayEntered.countDown();
                    try {
                        releaseGateway.await();
                    } catch (InterruptedException interruptedException) {
                        // 本合同模拟吞掉中断并正常返回的外部网关。
                    }
                    gatewayReturned.countDown();
                    return ToolInvocationResult.succeeded("取消后的结果");
                }, objectMapper, runGate);

        CancelAwaitingExecutor executor = new CancelAwaitingExecutor(gatewayReturned);
        Scheduler scheduler = Schedulers.fromExecutorService(executor);
        try {
            Disposable call = bridge.callAsync(toolCallParam())
                    .subscribeOn(scheduler)
                    .subscribe();
            assertThat(gatewayEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);

            call.dispose();
            releaseGateway.countDown();
            assertThat(gatewayReturned.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(runGate.isToolTimedOut()).isFalse();
            assertThat(bridge.records()).isEmpty();
            assertThatThrownBy(() -> bridge.callAsync(toolCallParam("后续调用", "tool-call-2", "echo")).block())
                    .isInstanceOf(AgentScopeRunGate.RunAbortedException.class);
            assertThat(gatewayCount).hasValue(1);
        } finally {
            releaseGateway.countDown();
            scheduler.dispose();
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancellationGateClosesBeforeCancellationReachesUpstream() {
        AgentScopeRunGate runGate = new AgentScopeRunGate(Duration.ofSeconds(1));
        AgentScopeToolBridge bridge = new AgentScopeToolBridge(
                request(), tool(validSchema()), ignored -> ToolInvocationResult.succeeded("ok"),
                objectMapper, runGate);
        AtomicReference<Boolean> gateStateAtUpstreamCancellation = new AtomicReference<>();
        Mono<ToolResultBlock> upstream = Mono.<ToolResultBlock>never()
                .doOnCancel(() -> gateStateAtUpstreamCancellation.set(
                        runGate.isInvocationInterrupted()));

        Disposable call = bridge.withCancellationGate(upstream).subscribe();
        call.dispose();

        assertThat(gateStateAtUpstreamCancellation).hasValue(true);
    }

    @Test
    void fatalFailureAfterConfiguredTimeoutDoesNotMarkToolTimeout() throws Exception {
        AgentScopeRunGate runGate = new AgentScopeRunGate(Duration.ofMillis(20));
        ToolInvocationInfrastructureException failure = new ToolInvocationInfrastructureException(
                "审计写入失败", new IllegalStateException("数据库不可用"));
        AgentScopeToolBridge bridge = new AgentScopeToolBridge(
                request(), tool(validSchema()), ignored -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    throw failure;
                }, objectMapper, runGate);

        assertThatThrownBy(() -> bridge.callAsync(toolCallParam()).block()).isSameAs(failure);
        assertThat(runGate.isToolTimedOut()).isFalse();
    }

    private AgentScopeToolBridge bridge(
            AgentRunRequest request,
            ToolDefinition tool,
            ToolInvocationGateway gateway
    ) {
        return new AgentScopeToolBridge(request, tool, gateway, objectMapper);
    }

    private static String outputText(ToolResultBlock block) {
        return ((TextBlock) block.getOutput().getFirst()).getText();
    }

    private static ToolCallParam toolCallParam() {
        Map<String, Object> input = Map.of("value", "hello");
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id("tool-call-1")
                .name("echo")
                .input(input)
                .build();
        return ToolCallParam.builder()
                .toolUseBlock(toolUse)
                .input(input)
                .runtimeContext(RuntimeContext.builder().sessionId(RUN_ID.toString()).build())
                .build();
    }

    private static ToolCallParam toolCallParam(int index) {
        return toolCallParam("hello-" + index, "tool-call-" + index);
    }

    private static ToolCallParam toolCallParam(String value) {
        return toolCallParam(value, "tool-call-sensitive");
    }

    private static ToolCallParam toolCallParam(String value, String toolCallId) {
        return toolCallParam(value, toolCallId, "echo");
    }

    private static ToolInvocationRequest invocationRequest(String toolCallId) {
        return new ToolInvocationRequest(
                TENANT_ID, AGENT_ID,
                new PrincipalRef(TENANT_ID, "principal", "测试主体", Set.of("agent:run")),
                RUN_ID, toolCallId, TOOL_ID, "echo", "{}");
    }

    private static boolean awaitWaiting(
            AtomicReference<Thread> threadReference,
            Duration timeout
    ) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Thread thread = threadReference.get();
            if (thread != null && (thread.getState() == Thread.State.BLOCKED
                    || thread.getState() == Thread.State.WAITING
                    || thread.getState() == Thread.State.TIMED_WAITING)) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private static final class CancelAwaitingExecutor extends ThreadPoolExecutor {

        private final CountDownLatch gatewayReturned;

        private CancelAwaitingExecutor(CountDownLatch gatewayReturned) {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
            this.gatewayReturned = gatewayReturned;
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
            return delayedCancelTask(callable);
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
            return delayedCancelTask(() -> {
                runnable.run();
                return value;
            });
        }

        private <T> RunnableFuture<T> delayedCancelTask(Callable<T> callable) {
            return new FutureTask<>(callable) {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    boolean cancelled = super.cancel(mayInterruptIfRunning);
                    try {
                        gatewayReturned.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
                    return cancelled;
                }
            };
        }
    }

    private static ToolCallParam toolCallParam(String value, String toolCallId, String toolName) {
        Map<String, Object> input = Map.of("value", value);
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id(toolCallId)
                .name(toolName)
                .input(input)
                .build();
        return ToolCallParam.builder()
                .toolUseBlock(toolUse)
                .input(input)
                .runtimeContext(RuntimeContext.builder().sessionId(RUN_ID.toString()).build())
                .build();
    }

    private static AgentRunRequest request() {
        AgentDefinition agent = new AgentDefinition(
                AGENT_ID, TENANT_ID, "企业助手", "", "你是企业助手", MODEL_ID,
                "agent-model", 0.2, 5, true, List.of(TOOL_ID), "tester", "tester");
        ModelConfig modelConfig = new ModelConfig(
                MODEL_ID, TENANT_ID, ModelProviderType.OPENAI_COMPATIBLE,
                "OpenAI兼容", "https://example.invalid/v1", "default-model", true);
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "系统管理员", Set.of("agent:run"));
        ToolDefinition tool = tool(validSchema());
        return new AgentRunRequest(RUN_ID, TENANT_ID, agent, modelConfig, principal, "调用工具", List.of(tool));
    }

    private static ToolDefinition tool(String inputSchema) {
        return new ToolDefinition(
                TOOL_ID, TENANT_ID, "echo", "回显输入", ToolType.LOCAL,
                inputSchema, ToolRiskLevel.LOW, true, "", "tester", "tester");
    }

    private static String validSchema() {
        return "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}";
    }
}
