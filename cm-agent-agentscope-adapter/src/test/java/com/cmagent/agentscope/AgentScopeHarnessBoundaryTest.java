package com.cmagent.agentscope;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 将当前 CM Agent 的工具桥接器和权限规则送入真实 Harness 2.0.2 事件流。
 *
 * <p>模型请求仅进入回环地址上的合成 SSE 服务；没有外部凭据、数据库或真实工具副作用。
 * 每例使用独立临时工作区，测试方持有 HTTP 服务、线程池和检查点，结束时负责关闭/清理。
 * 本类只证明框架合同，不能代替服务端审批 API、加密仓储和生产模型验收。</p>
 */
@Timeout(15)
class AgentScopeHarnessBoundaryTest {
    /** 仅用于本地 Stub 的无效凭据，不从用户配置读取。 */
    private static final ModelCredential CREDENTIAL = new ModelCredential("invalid-harness-local-key");
    /** 合成租户，身份来自测试构造的服务端请求而非模型输入。 */
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** 每例独立目录，禁止把仓库、用户主目录或部署配置作为工作区。 */
    @TempDir Path workspace;
    /** 测试方拥有的 HTTP 服务，结束时立即停止。 */
    private HttpServer server;
    /** 测试方拥有的回环 HTTP 处理线程池。 */
    private ExecutorService serverExecutor;
    /** 仅保存合成请求供断言，不打印原始载荷。 */
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    /** 记录到达 Stub 的时点，用于有界取消/超时实验。 */
    private final CountDownLatch arrived = new CountDownLatch(1);
    /** 工具响应的名称，可在负向实验中要求一个不在白名单中的工具。 */
    private String requestedTool = "echo";
    /** 仅保存合成工具参数，序列化时作为协议字符串嵌入 SSE。 */
    private String toolArguments = "{\"value\":\"合成输入\"}";
    /** 合成 Provider 延迟，用于验证取消和超时。 */
    private volatile boolean slow;
    /** 生产隔离对象由测试持有，框架关闭后检查目录清理。 */
    private final List<AgentScopeHarnessIsolation> isolations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newFixedThreadPool(2);
        server.setExecutor(serverExecutor);
        server.createContext("/v1/chat/completions", this::respond);
        server.start();
    }

    @AfterEach
    void closeStub() {
        server.stop(0);
        serverExecutor.shutdownNow();
        isolations.forEach(isolation -> {
            var path = isolation.workspace();
            isolation.check();
            isolation.close();
            assertThat(Files.exists(path)).isFalse();
        });
    }

    @Test
    void 构建后只有受治理工具且原Toolkit没有新增工具() {
        var request = request(TENANT, UUID.randomUUID(), ToolRiskLevel.LOW);
        var toolkit = new Toolkit();
        var gate = new AgentScopeRunGate(Duration.ofSeconds(1));
        var bridge = new AgentScopeToolBridge(request, request.tools().getFirst(),
                ignored -> ToolInvocationResult.succeeded("合成结果"), new ObjectMapper(), gate);
        toolkit.registerAgentTool(bridge);
        try (var base = base(request, toolkit, new InMemoryAgentStateStore());
             var harness = controlled(base)) {
            assertThat(harness.getToolkit().getToolNames()).containsExactly("echo");
            assertThat(toolkit.getToolNames()).containsExactly("echo");
            assertThat(harness.getToolkit()).isNotSameAs(toolkit);
            assertThat(harness.getToolkit().getTool("echo")).isSameAs(bridge);
            assertThat(harness.getStateStore()).isSameAs(base.getStateStore());
            assertThat(harness.getDelegate().getPermissionContext()).isSameAs(base.getPermissionContext());
            assertThat(harness.getDelegate().getModelExecutionConfig()).isEqualTo(base.getModelExecutionConfig());
            assertThat(harness.getDelegate().getToolExecutionConfig()).isEqualTo(base.getToolExecutionConfig());
            assertThat(harness.getCompactionHook()).isNull();
            assertThat(harness.getSkillRepositories()).isEmpty();
        }
        assertNoGeneratedFiles();
    }

    @Test
    void 实际事件流带文本增量且每次工具仍经过可信网关() {
        var invocations = new CopyOnWriteArrayList<ToolInvocationRequest>();
        var request = request(TENANT, UUID.randomUUID(), ToolRiskLevel.LOW);
        try (var fixture = fixture(request, invocation -> {
            invocations.add(invocation);
            return ToolInvocationResult.succeeded("治理结果");
        }, new InMemoryAgentStateStore())) {
            var events = run(fixture, new UserMessage("调用工具"));
            assertThat(events).anyMatch(TextBlockDeltaEvent.class::isInstance)
                    .anyMatch(AgentResultEvent.class::isInstance);
            assertThat(invocations).singleElement().satisfies(call -> {
                assertThat(call.tenantId()).isEqualTo(TENANT);
                assertThat(call.runId()).isEqualTo(request.runId());
                assertThat(call.principal()).isEqualTo(request.principal());
                assertThat(call.toolId()).isEqualTo(request.tools().getFirst().id());
            });
            assertThat(bodies).hasSize(2);
            assertThat(bodies.getLast()).contains("治理结果");
        }
        assertNoGeneratedFiles();
    }

    @Test
    void 禁止文件工具不会因模型要求而进入网关() {
        requestedTool = "read_file";
        var count = new AtomicInteger();
        var request = request(TENANT, UUID.randomUUID(), ToolRiskLevel.LOW);
        try (var fixture = fixture(request, ignored -> {
            count.incrementAndGet();
            return ToolInvocationResult.succeeded("不应调用");
        }, new InMemoryAgentStateStore())) {
            run(fixture, new UserMessage("调用工具"));
            assertThat(count).hasValue(0);
            assertThat(fixture.harness().getToolkit().getToolNames()).containsExactly("echo");
        }
        assertNoGeneratedFiles();
    }

    @Test
    void 默认异步等待工具被白名单移除且模型请求不能进入网关() {
        requestedTool = "wait_async_results";
        var count = new AtomicInteger();
        var request = request(TENANT, UUID.randomUUID(), ToolRiskLevel.LOW);
        try (var fixture = fixture(request, ignored -> {
            count.incrementAndGet();
            return ToolInvocationResult.succeeded("不应调用");
        }, new InMemoryAgentStateStore())) {
            run(fixture, new UserMessage("调用异步等待工具"));
            assertThat(count).hasValue(0);
            assertThat(fixture.harness().getToolkit().getToolNames()).containsExactly("echo");
            assertThat(bodies.getFirst()).doesNotContain("wait_async_results");
        }
        assertNoGeneratedFiles();
    }

    @Test
    void 调用方目录的配置记忆和共享Skill不能进入受控工作区() throws IOException {
        var seeded = Map.of("AGENTS.md", "宿主提示注入标记",
                "MEMORY.md", "宿主记忆注入标记",
                "tools.json", "{\"tools\":[],\"marker\":\"宿主工具注入标记\"}",
                "skills/untrusted/SKILL.md", "---\nname: untrusted\ndescription: 宿主技能注入标记\n---\n宿主技能正文标记");
        for (var entry : seeded.entrySet()) {
            var path = workspace.resolve(entry.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, entry.getValue(), StandardCharsets.UTF_8);
        }
        var request = request(TENANT, UUID.randomUUID(), ToolRiskLevel.LOW);
        try (var fixture = fixture(request, ignored -> ToolInvocationResult.succeeded("受控结果"),
                new InMemoryAgentStateStore())) {
            run(fixture, new UserMessage("执行受控工具"));
            assertThat(fixture.harness().getSkillRepositories()).isEmpty();
            assertThat(bodies).allSatisfy(body -> assertThat(body)
                    .doesNotContain("宿主提示注入标记", "宿主记忆注入标记", "宿主工具注入标记",
                            "宿主技能注入标记", "宿主技能正文标记"));
        }
        try (var files = Files.walk(workspace)) {
            assertThat(files.filter(Files::isRegularFile).map(workspace::relativize).toList())
                    .containsExactlyInAnyOrderElementsOf(seeded.keySet().stream().map(Path::of).toList());
        }
        for (var entry : seeded.entrySet()) {
            assertThat(Files.readString(workspace.resolve(entry.getKey()), StandardCharsets.UTF_8))
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    void ASK暂停关闭重建后ConfirmResult只执行一次且终态由宿主删除() {
        var store = new InMemoryAgentStateStore();
        var count = new AtomicInteger();
        var request = request(TENANT, UUID.randomUUID(), ToolRiskLevel.HIGH);
        List<ToolUseBlock> asking;
        RuntimeContext context;
        try (var first = fixture(request, ignored -> {
            count.incrementAndGet();
            return ToolInvocationResult.succeeded("已批准");
        }, store)) {
            assertThat(run(first, new UserMessage("调用工具")))
                    .anyMatch(RequireUserConfirmEvent.class::isInstance);
            asking = asking(first);
            context = first.context();
            assertThat(asking).hasSize(1);
            assertThat(count).hasValue(0);
        }
        assertThat(store.exists(context.getUserId(), context.getSessionId())).isTrue();
        try (var resumed = fixture(request, ignored -> {
            count.incrementAndGet();
            return ToolInvocationResult.succeeded("已批准");
        }, store)) {
            run(resumed, confirmation(asking, true));
            assertThat(count).hasValue(1);
            assertThat(asking(resumed)).isEmpty();
        }
        // Harness.close() 只关闭资源；CM Agent 必须保留原有“仅 ASK 保留槽”的宿主清理规则。
        assertThat(store.exists(context.getUserId(), context.getSessionId())).isTrue();
        store.delete(context.getUserId(), context.getSessionId());
        assertThat(store.exists(context.getUserId(), context.getSessionId())).isFalse();
    }

    @Test
    void 审批拒绝不调用业务网关() {
        var count = new AtomicInteger();
        var request = request(TENANT, UUID.randomUUID(), ToolRiskLevel.HIGH);
        try (var fixture = fixture(request, ignored -> {
            count.incrementAndGet();
            return ToolInvocationResult.succeeded("不应执行");
        }, new InMemoryAgentStateStore())) {
            run(fixture, new UserMessage("调用工具"));
            run(fixture, confirmation(asking(fixture), false));
            assertThat(count).hasValue(0);
        }
    }

    /** 当前平台规则必须明确 DENY，而不能期待框架把显式 ASK 转换为拒绝。 */
    @Test
    void 无会话HIGH明确拒绝不产生确认事件或实际调用() {
        var count = new AtomicInteger();
        var request = request(TENANT, UUID.randomUUID(), ToolRiskLevel.HIGH);
        var noConversation = new AgentRunRequest(request.runId(), request.tenantId(), request.agent(),
                request.modelConfig(), request.principal(), request.input(), request.tools());
        try (var fixture = fixture(noConversation, ignored -> {
            count.incrementAndGet();
            return ToolInvocationResult.succeeded("不应执行");
        }, new InMemoryAgentStateStore())) {
            assertThat(run(fixture, new UserMessage("调用工具")))
                    .noneMatch(RequireUserConfirmEvent.class::isInstance)
                    .anyMatch(event -> event instanceof ToolResultEndEvent end
                            && end.getState() == ToolResultState.DENIED);
            assertThat(fixture.harness().getDelegate().getPermissionContext().getMode())
                    .isEqualTo(io.agentscope.core.permission.PermissionMode.DONT_ASK);
            assertThat(count).hasValue(0);
        }
    }

    @Test
    void 固定Skill桥接器经Toolkit复制后仍通过治理网关读取() throws IOException {
        var original = AgentScopeSkillSessionTest.request();
        var model = new ModelConfig(original.modelConfig().id(), original.tenantId(),
                ModelProviderType.OPENAI_COMPATIBLE, "回环模型",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model", true);
        var request = new AgentRunRequest(original.runId(), original.tenantId(), original.agent(), model,
                original.principal(), original.input(), original.tools(), null, original.skills());
        var reads = new CopyOnWriteArrayList<SkillReadRequest>();
        var gate = new AgentScopeRunGate(Duration.ofSeconds(1));
        var toolkit = new Toolkit();
        var skill = request.skills().getFirst();
        var nativeId = new AgentScopeSkillRepository(request.skills()).getSkill(skill.definition().name()).getSkillId();
        requestedTool = AgentScopeSkillLoadBridge.TOOL_NAME;
        toolArguments = new ObjectMapper().writeValueAsString(Map.of("skillId", nativeId, "path", "SKILL.md"));
        try (var session = new AgentScopeSkillSession(request, toolkit, gate, (read, loader) -> {
            reads.add(read);
            return new SkillReadResult(loader.get(), UUID.randomUUID());
        }); var base = base(request, toolkit, new InMemoryAgentStateStore()); var harness = controlled(base)) {
            var context = RuntimeContext.builder().userId(request.tenantId() + ":tester")
                    .sessionId(request.runId().toString()).build();
            harness.streamEvents(new UserMessage("读取固定Skill"), context).doOnNext(event -> gate.throwIfSkillFailure())
                    .blockLast(Duration.ofSeconds(8));
            assertThat(harness.getToolkit().getToolNames()).containsExactly(AgentScopeSkillLoadBridge.TOOL_NAME);
            assertThat(reads).singleElement().satisfies(read -> {
                assertThat(read.skillId()).isEqualTo(skill.definition().id());
                assertThat(read.versionId()).isEqualTo(skill.version().id());
                assertThat(read.principal()).isEqualTo(request.principal());
                assertThat(read.runId()).isEqualTo(request.runId());
            });
            assertThat(bodies.getLast()).contains("仅限本轮加载的正文");
            assertThat(harness.getToolkit().getTool("run_skill_code")).isNull();
            assertThat(harness.getToolkit().getTool("run_skill_script")).isNull();
        }
        assertNoGeneratedFiles();
    }

    @Test
    void 批准后仍经过二次授权且拒绝不产生成功记录() {
        var store = new InMemoryAgentStateStore();
        var request = request(TENANT, UUID.randomUUID(), ToolRiskLevel.HIGH);
        List<ToolUseBlock> asking;
        try (var first = fixture(request, ignored -> ToolInvocationResult.succeeded("不应调用"), store)) {
            run(first, new UserMessage("调用工具"));
            asking = asking(first);
        }
        var calls = new AtomicInteger();
        try (var resumed = fixture(request, ignored -> {
            calls.incrementAndGet();
            return ToolInvocationResult.denied("授权已撤销");
        }, store)) {
            run(resumed, confirmation(asking, true));
            assertThat(calls).hasValue(1);
            assertThat(resumed.bridge().records()).singleElement()
                    .satisfies(record -> assertThat(record.status()).isEqualTo(RunStatus.DENIED));
        }
    }

    @Test
    void 同名主体跨租户及不同Run不复用审批状态() {
        var store = new InMemoryAgentStateStore();
        var runId = UUID.randomUUID();
        var requestA = request(TENANT, runId, ToolRiskLevel.HIGH);
        try (var a = fixture(requestA, ignored -> ToolInvocationResult.succeeded("不应调用"), store)) {
            run(a, new UserMessage("调用工具"));
            assertThat(asking(a)).hasSize(1);
        }
        var requestB = request(UUID.randomUUID(), runId, ToolRiskLevel.HIGH);
        var requestC = request(TENANT, UUID.randomUUID(), ToolRiskLevel.HIGH);
        for (var request : List.of(requestB, requestC)) {
            try (var isolated = fixture(request, ignored -> ToolInvocationResult.succeeded("不应调用"), store)) {
                assertThat(asking(isolated)).isEmpty();
            }
        }
    }

    @Test
    void 严格基础设施失败在Harness事件边界重新抛出() {
        var failure = new ToolInvocationInfrastructureException("合成严格审计失败", new IllegalStateException("合成失败"));
        var request = request(TENANT, UUID.randomUUID(), ToolRiskLevel.LOW);
        try (var fixture = fixture(request, ignored -> { throw failure; }, new InMemoryAgentStateStore())) {
            assertThatThrownBy(() -> run(fixture, new UserMessage("调用工具"))).isSameAs(failure);
            assertThat(bodies).hasSize(1);
            assertThat(fixture.bridge().records()).isEmpty();
        }
    }

    @Test
    void 取消订阅与运行信号阻止后续工具且关闭有界() throws Exception {
        slow = true;
        var calls = new AtomicInteger();
        var request = request(TENANT, UUID.randomUUID(), ToolRiskLevel.LOW);
        try (var fixture = fixture(request, ignored -> {
            calls.incrementAndGet();
            return ToolInvocationResult.succeeded("不应执行");
        }, new InMemoryAgentStateStore())) {
            var subscription = fixture.harness().streamEvents(new UserMessage("调用工具"), fixture.context())
                    .takeUntilOther(reactor.core.publisher.Mono.fromFuture(request.cancellation().signal(), true))
                    .subscribe();
            try {
                assertThat(arrived.await(3, TimeUnit.SECONDS)).isTrue();
                request.cancellation().request();
                // 2.0.2 的 Harness 未暴露带 RuntimeContext 的中断重载，必须使用 delegate。
                fixture.harness().getDelegate().interrupt(fixture.context());
                subscription.dispose();
                request.cancellation().awaitQuiescence();
                assertThat(calls).hasValue(0);
            } finally {
                subscription.dispose();
            }
        }
    }

    @Test
    void Provider超时后关闭且没有工具副作用() {
        slow = true;
        var request = request(TENANT, UUID.randomUUID(), ToolRiskLevel.LOW);
        var calls = new AtomicInteger();
        try (var fixture = fixture(request, ignored -> {
            calls.incrementAndGet();
            return ToolInvocationResult.succeeded("不应执行");
        }, new InMemoryAgentStateStore())) {
            assertThatThrownBy(() -> fixture.harness().streamEvents(new UserMessage("调用工具"), fixture.context())
                    .blockLast(Duration.ofSeconds(3))).hasStackTraceContaining("timeout");
            assertThat(calls).hasValue(0);
        }
    }

    /** 直接验证正式模块的构造与隔离对象；不使用 Mockito 文件系统或总线替身。 */
    private HarnessAgent controlled(ReActAgent base) {
        var isolation = new AgentScopeHarnessIsolation();
        isolations.add(isolation);
        return AgentScopeHarnessExecutor.HarnessLifecycle.build(base, isolation);
    }

    private ReActAgent base(AgentRunRequest request, Toolkit toolkit, InMemoryAgentStateStore store) {
        return ReActAgent.builder().name("兼容实验").sysPrompt("只使用本次授权工具")
                .model(new AgentScopeModelFactory().create(request.modelConfig(), request.agent(), CREDENTIAL))
                .toolkit(toolkit).maxIters(3).stateStore(store).defaultSessionId(request.runId().toString())
                .permissionContext(AgentScopeReActExecutor.permissionContext(
                        request.tools(), true, request.conversationId() != null))
                .modelExecutionConfig(ExecutionConfig.builder().maxAttempts(1)
                        .timeout(Duration.ofMillis(slow ? 250 : 3000)).build())
                .toolExecutionConfig(ExecutionConfig.builder().maxAttempts(1).timeout(Duration.ofSeconds(1)).build())
                .enableMetaTool(false).enableTaskList(false).build();
    }

    /** 每次恢复重新建桥接器；治理调用永远捕获当前可信领域请求。 */
    private Fixture fixture(AgentRunRequest request, ToolInvocationGateway gateway, InMemoryAgentStateStore store) {
        var gate = new AgentScopeRunGate(Duration.ofSeconds(1));
        gate.bindCancellation(request.cancellation());
        var toolkit = new Toolkit();
        var bridge = new AgentScopeToolBridge(request, request.tools().getFirst(), gateway, new ObjectMapper(), gate);
        toolkit.registerAgentTool(bridge);
        var base = base(request, toolkit, store);
        try {
            var harness = controlled(base);
            var context = RuntimeContext.builder().userId(request.tenantId() + ":" + request.principal().principalId())
                    .sessionId(request.runId().toString()).build();
            return new Fixture(base, harness, context, bridge, gate);
        } catch (Throwable failure) {
            base.close();
            throw failure;
        }
    }

    /** 框架可能把工具异常包装成结果，边界门控必须恢复严格失败。 */
    private List<AgentEvent> run(Fixture fixture, Msg message) {
        return fixture.harness().streamEvents(message, fixture.context()).doOnNext(event -> {
            fixture.gate().throwIfInfrastructureFailure();
            fixture.gate().throwIfSkillFailure();
        }).collectList().block(Duration.ofSeconds(8));
    }

    private List<ToolUseBlock> asking(Fixture fixture) {
        return fixture.harness().getDelegate().getAgentState(fixture.context()).getContext().stream()
                .flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                .filter(block -> block.getState() == ToolCallState.ASKING).toList();
    }

    private Msg confirmation(List<ToolUseBlock> asking, boolean approved) {
        return Msg.builder().name("user").role(MsgRole.USER).textContent("合成审批决定")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS,
                        asking.stream().map(block -> new ConfirmResult(approved, block)).toList())).build();
    }

    private AgentRunRequest request(UUID tenant, UUID runId, ToolRiskLevel risk) {
        var modelId = UUID.randomUUID();
        var tool = new ToolDefinition(UUID.randomUUID(), tenant, "echo", "合成治理工具", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}",
                risk, true, "", "tester", "tester");
        var agent = new AgentDefinition(UUID.randomUUID(), tenant, "兼容实验", "", "合成提示", modelId,
                "test-model", 0.2, 3, true, List.of(tool.id()), "tester", "tester");
        var model = new ModelConfig(modelId, tenant, ModelProviderType.OPENAI_COMPATIBLE, "回环模型",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model", true);
        return new AgentRunRequest(runId, tenant, agent, model,
                new PrincipalRef(tenant, "same-principal", "合成主体", Set.of("agent:run")),
                "合成输入", List.of(tool), UUID.randomUUID());
    }

    private void assertNoGeneratedFiles() {
        try (var files = Files.walk(workspace)) {
            assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
        } catch (IOException failure) {
            throw new IllegalStateException("实验工作区检查失败", failure);
        }
    }

    /** 回环 Stub 支持一次工具调用和后续最终回答，不依据真实模型行为得出生产结论。 */
    private void respond(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.add(body);
        arrived.countDown();
        if (slow) {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
        }
        boolean toolResult = body.contains("\"role\":\"tool\"");
        String message = toolResult
                ? "\"content\":\"合成最终回答\""
                : "\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":"
                    + "{\"name\":\"" + requestedTool + "\",\"arguments\":"
                    + new ObjectMapper().writeValueAsString(toolArguments) + "}}]";
        String response = "{\"id\":\"stub\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"," + message
                + "},\"finish_reason\":\"" + (toolResult ? "stop" : "tool_calls") + "\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
        byte[] data = ("data: " + response + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        try {
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
        } finally {
            exchange.close();
        }
    }

    /**
     * 测试方持有基础 Agent 与 Harness，两者使用复制后的独立 Toolkit。
     *
     * @param base 用于 fromAgent 的基础配置源，Harness 关闭后另行关闭
     * @param harness 实际执行事件流并负责关闭 delegate 的框架入口
     * @param context 由可信测试请求派生的租户/Run 身份
     * @param bridge 当前运行共享门控的业务桥接器
     * @param gate 恢复严格失败并防止迟到结果的运行门控
     */
    private record Fixture(ReActAgent base, HarnessAgent harness, RuntimeContext context,
                           AgentScopeToolBridge bridge, AgentScopeRunGate gate) implements AutoCloseable {
        @Override
        public void close() {
            try {
                harness.close();
            } finally {
                base.close();
            }
        }
    }
}
