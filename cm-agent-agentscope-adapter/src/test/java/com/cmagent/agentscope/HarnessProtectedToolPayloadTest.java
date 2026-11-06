package com.cmagent.agentscope;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;

/**
 * 生产 HarnessExecutor/Lifecycle 经回环合成 Provider 验证项目载荷合同，不替换事件流或复制治理实现。
 * HTTP 服务、线程池及内存检查点由本测试创建并关闭；不读取用户项目、不执行 shell，也不代表真实 Provider 验收。
 */
@Timeout(20)
@ExtendWith(OutputCaptureExtension.class)
class HarnessProtectedToolPayloadTest {
    /** 合成 Provider 的 JSON 编码器，原始载荷不写日志。 */
    private final ObjectMapper json = new ObjectMapper();
    /** 仅捕获合成请求供断言，不记录真实用户数据。 */
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    /** 测试独占回环服务，结束时立即停止。 */
    private HttpServer server;
    /** HTTP 工作线程由测试统一中断关闭。 */
    private ExecutorService serverThreads;
    /** 第一条请求到达后才触发取消，避免用未开始的运行假装证明停止。 */
    private final CountDownLatch arrived = new CountDownLatch(1);
    /** 仅在取消场景保持 Provider 响应待定。 */
    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    /** 模型要求的工具名，负向场景可故意请求原生工具。 */
    private volatile String requestedTool = "read_owned_file";
    /** 取消场景延迟由测试持有的闩锁控制。 */
    private volatile boolean delayed;

    @BeforeEach void startProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newFixedThreadPool(2);
        server.setExecutor(serverThreads);
        server.createContext("/v1/chat/completions", this::respond);
        server.start();
    }

    @AfterEach void closeProvider() {
        releaseResponse.countDown();
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @Test void 项目能力声明跟随真实审批开关() {
        assertThat(executor(new InMemoryAgentStateStore(), true).supportsLocalProjects()).isTrue();
        assertThat(executor(new InMemoryAgentStateStore(), false).supportsLocalProjects()).isFalse();
    }

    @Test void 项目系统提示明确列出本轮固定工具并纠正原生文件系统隔离含义() {
        var request = request(ToolRiskLevel.LOW, "project_read_file");
        String instructions = AgentScopeReActExecutor.projectToolInstructions(request);
        assertThat(instructions)
                .contains("本轮暴露的项目工具", "`project_read_file`", "原生文件系统和 Shell 已按安全策略禁用")
                .contains("不表示用户没有授权项目文件", "不得编造正文", "不得在存在项目工具时要求用户粘贴文件内容")
                .contains("当前模式：帮我自动批准")
                .doesNotContain(request.projectContext().binding().rootIdentity());
    }

    @Test void 生产Harness只向模型交付文件正文且安全摘要思考日志不夹带正文(CapturedOutput logs) {
        var request = request(ToolRiskLevel.LOW);
        var invocations = new CopyOnWriteArrayList<ToolInvocationRequest>();
        var progress = new CopyOnWriteArrayList<AgentProgressEvent>();
        var executor = executor(new InMemoryAgentStateStore(), true);
        var result = executor.executeStructured(new AgentScopeRunSpec(request), credential(), call -> {
            invocations.add(call);
            return ToolInvocationResult.succeededProtected("PRIVATE_FILE_BODY", "文件读取完成，1项");
        }, ignored -> {}, progress::add);
        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.output()).isEqualTo("合成最终回答");
        assertThat(invocations).singleElement().satisfies(call -> {
            assertThat(call.tenantId()).isEqualTo(request.projectContext().binding().tenantId());
            assertThat(call.agentId()).isEqualTo(request.projectContext().binding().agentId());
            assertThat(call.runId()).isEqualTo(request.projectContext().binding().runId());
            assertThat(call.principal()).isEqualTo(request.principal());
            assertThat(request.projectContext().protectedToolIds()).containsExactly(call.toolId());
            assertThat(call.inputJson()).contains("PRIVATE_PATCH_INPUT");
        });
        assertThat(bodies).hasSize(2);
        assertThat(bodies.getLast()).contains("PRIVATE_FILE_BODY");
        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.inputSummary()).isEqualTo(AgentScopeToolBridge.PROTECTED_INPUT_SUMMARY);
            assertThat(call.outputSummary()).isEqualTo("文件读取完成，1项");
        });
        assertThat(progress.toString()).doesNotContain("PRIVATE_FILE_BODY", "PRIVATE_PATCH_INPUT", "PRIVATE_THINKING_BODY");
        assertThat(result.assistantMessage().toString()).doesNotContain("PRIVATE_FILE_BODY", "PRIVATE_PATCH_INPUT", "PRIVATE_THINKING_BODY");
        assertThat(logs).doesNotContain("PRIVATE_FILE_BODY", "PRIVATE_PATCH_INPUT", "PRIVATE_THINKING_BODY");
    }

    @Test void 高风险项目ASK使用精确输入哈希且恢复只经原治理网关一次(CapturedOutput logs) {
        var request = request(ToolRiskLevel.HIGH);
        var executor = executor(new InMemoryAgentStateStore(), true);
        var count = new AtomicInteger();
        ToolInvocationGateway gateway = call -> {
            count.incrementAndGet();
            assertThat(call.toolId()).isEqualTo(request.tools().getFirst().id());
            assertThat(call.runId()).isEqualTo(request.projectContext().binding().runId());
            return ToolInvocationResult.succeededProtected("PRIVATE_FILE_BODY", "操作完成，1项");
        };
        var waiting = executor.executeStructured(new AgentScopeRunSpec(request), credential(), gateway, ignored -> {}, ignored -> {});
        assertThat(waiting.status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(count).hasValue(0);
        var pending = waiting.pendingApproval().items().getFirst();
        assertThat(pending.inputSummary()).isEqualTo(AgentScopeToolBridge.PROTECTED_INPUT_SUMMARY);
        assertThat(pending.inputHash()).isEqualTo(AgentScopeReActExecutor.approvalInputHash(json, Map.of("value", "PRIVATE_PATCH_INPUT")));
        assertThat(waiting.pendingApproval().toString()).doesNotContain("PRIVATE_PATCH_INPUT", "PRIVATE_THINKING_BODY");
        var decisions = new RuntimeApprovalDecisions(List.of(new RuntimeApprovalDecisions.Item(pending.toolCallId(), pending.toolId(),
                pending.inputHash(), ToolApprovalDecision.APPROVE)));
        var resumed = executor.resumeStructured(new AgentScopeRunSpec(request), credential(), gateway, decisions, ignored -> {}, ignored -> {});
        assertThat(resumed.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(count).hasValue(1);
        assertThat(bodies.getLast()).contains("PRIVATE_FILE_BODY");
        assertThat(resumed.toolCalls().toString()).doesNotContain("PRIVATE_FILE_BODY", "PRIVATE_PATCH_INPUT");
        assertThatThrownBy(() -> executor.resumeStructured(new AgentScopeRunSpec(request), credential(), gateway, decisions, ignored -> {}, ignored -> {}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("检查点不存在");
        assertThat(count).hasValue(1);
        assertThat(logs).doesNotContain("PRIVATE_FILE_BODY", "PRIVATE_PATCH_INPUT", "PRIVATE_THINKING_BODY");
    }

    @Test void 高风险项目恢复拒绝被篡改的输入哈希且不产生副作用() {
        var request = request(ToolRiskLevel.HIGH);
        var executor = executor(new InMemoryAgentStateStore(), true);
        var count = new AtomicInteger();
        ToolInvocationGateway gateway = ignored -> { count.incrementAndGet(); return ToolInvocationResult.succeeded("不应执行"); };
        var waiting = executor.executeStructured(new AgentScopeRunSpec(request), credential(), gateway, ignored -> {}, ignored -> {});
        var item = waiting.pendingApproval().items().getFirst();
        var decisions = new RuntimeApprovalDecisions(List.of(new RuntimeApprovalDecisions.Item(item.toolCallId(), item.toolId(),
                "0".repeat(64), ToolApprovalDecision.APPROVE)));
        assertThatThrownBy(() -> executor.resumeStructured(new AgentScopeRunSpec(request), credential(), gateway, decisions, ignored -> {}, ignored -> {}))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("输入已变化");
        assertThat(count).hasValue(0);
    }

    @Test void 模型要求原生文件或Shell工具也不能越过固定白名单() {
        for (String nativeTool : List.of("read_file", "execute_shell_command")) {
            bodies.clear();
            requestedTool = nativeTool;
            var calls = new AtomicInteger();
            var request = request(ToolRiskLevel.LOW);
            executor(new InMemoryAgentStateStore(), true).executeStructured(new AgentScopeRunSpec(request), credential(), ignored -> {
                calls.incrementAndGet();
                return ToolInvocationResult.succeeded("不应执行");
            }, ignored -> {}, ignored -> {});
            assertThat(calls).hasValue(0);
            var first = readJson(bodies.getFirst());
            var names = new ArrayList<String>();
            first.path("tools").forEach(tool -> names.add(tool.path("function").path("name").asText()));
            assertThat(names).containsExactly("read_owned_file");
            assertThat(first.toString())
                    .contains("本轮暴露的项目工具", "`read_owned_file`", "原生文件系统和 Shell 已按安全策略禁用")
                    .contains("不得在存在项目工具时要求用户粘贴文件内容");
        }
    }

    @Test void 生产Harness项目等待模型期间取消有界并禁止迟到工具() throws Exception {
        delayed = true;
        var request = request(ToolRiskLevel.LOW);
        var calls = new AtomicInteger();
        try (var worker = Executors.newSingleThreadExecutor()) {
            var future = worker.submit(() -> executor(new InMemoryAgentStateStore(), true).executeStructured(
                    new AgentScopeRunSpec(request), credential(), ignored -> {
                        calls.incrementAndGet();
                        return ToolInvocationResult.succeeded("不应执行");
                    }, ignored -> {}, ignored -> {}));
            try {
                assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
                request.cancellation().request();
                assertThat(future.get(5, TimeUnit.SECONDS).status()).isEqualTo(RunStatus.CANCELLED);
                assertThat(calls).hasValue(0);
            } finally {
                releaseResponse.countDown();
                request.cancellation().request();
            }
        }
    }

    private AgentScopeHarnessExecutor executor(InMemoryAgentStateStore store, boolean permissionEnabled) {
        return new AgentScopeHarnessExecutor(new AgentScopeRuntimeOptions(Duration.ofSeconds(10), Duration.ofSeconds(2), 1, permissionEnabled),
                store, (request, loader) -> { throw new AssertionError("本项目合同不应读取技能"); });
    }

    private AgentRunRequest request(ToolRiskLevel risk) {
        return request(risk, "read_owned_file");
    }

    private AgentRunRequest request(ToolRiskLevel risk, String toolName) {
        UUID tenant = UUID.randomUUID(), agentId = UUID.randomUUID(), modelId = UUID.randomUUID(), toolId = UUID.randomUUID();
        UUID runId = UUID.randomUUID(), conversationId = UUID.randomUUID();
        var tool = new ToolDefinition(toolId, tenant, toolName, "合成受治理项目工具", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}",
                risk, true, "", "Owner", "Owner");
        var agent = new AgentDefinition(agentId, tenant, "合成项目Harness", "", "仅使用本轮授权工具", modelId,
                "test-model", 0.2, 4, true, List.of(toolId), "Owner", "Owner");
        var model = new ModelConfig(modelId, tenant, ModelProviderType.OPENAI_COMPATIBLE, "回环合成模型",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model", true);
        var principal = new PrincipalRef(tenant, "Owner", "合成主体", Set.of("agent:run", "project:read"));
        var binding = new LocalProjectBinding(tenant, "Owner", UUID.randomUUID(), agentId, conversationId, runId,
                UUID.randomUUID(), "a".repeat(64), "local-node", 1, Instant.now());
        return new AgentRunRequest(runId, tenant, agent, model, principal, "读取合成文件", List.of(tool), conversationId,
                List.of(), Map.of(), new RunCancellation(), null, new ProjectRuntimeContext(binding, Set.of(toolId)));
    }

    private static ModelCredential credential() { return new ModelCredential("invalid-harness-project-fixture-key"); }

    private com.fasterxml.jackson.databind.JsonNode readJson(String value) {
        try { return json.readTree(value); }
        catch (IOException failure) { throw new AssertionError("合成请求不是合法JSON"); }
    }

    /** 真实 OpenAI SSE 解析链中注入合成思考、工具调用与最终回答，模型结果正文只在测试内断言。 */
    private void respond(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.add(body);
        arrived.countDown();
        if (delayed) {
            try { releaseResponse.await(12, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); exchange.close(); return; }
        }
        boolean resultSeen = body.contains("\"role\":\"tool\"");
        var message = new LinkedHashMap<String, Object>();
        message.put("role", "assistant");
        message.put("reasoning_content", "PRIVATE_THINKING_BODY");
        if (resultSeen) message.put("content", "合成最终回答");
        else {
            message.put("content", null);
            message.put("tool_calls", List.of(Map.of("id", "call-project-1", "type", "function", "function",
                    Map.of("name", requestedTool, "arguments", json.writeValueAsString(Map.of("value", "PRIVATE_PATCH_INPUT"))))));
        }
        String response = json.writeValueAsString(Map.of("id", "harness-project-stub", "object", "chat.completion", "created", 1,
                "model", "test-model", "choices", List.of(Map.of("index", 0, "message", message,
                        "finish_reason", resultSeen ? "stop" : "tool_calls")),
                "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
        byte[] data = ("data: " + response + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        try {
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
        } finally { exchange.close(); }
    }
}
