package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.agentscope.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.*;
import com.cmagent.core.tool.*;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 经过生产 Harness、加密 Store、治理网关、会话/审批编排与 Web 的合同回归。
 *
 * <p>仅模型响应来自回环协议桩；LOCAL 工具也是真正注册并经治理执行，不能把本测试等同于
 * 外部 Provider 断连或真实业务副作用验收。子类在 Rocky 两库重跑同一组合同。</p>
 */
@SpringBootTest(classes = CmAgentServerApplication.class, properties = {
        "cm-agent.fake-runtime-enabled=false", "cm-agent.agentscope.enabled=true",
        "cm-agent.agentscope.execution-mode=harness", "cm-agent.agentscope.studio.enabled=false",
        "cm-agent.agentscope.permission-enabled=true",
        "cm-agent.approval-expiry.enabled=false", "cm-agent.agentscope.model-max-attempts=1",
        "cm-agent.agentscope.model-timeout=10s", "cm-agent.agentscope.tool-timeout=10s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(HarnessApprovalFlowTest.Configuration.class)
@ExtendWith(OutputCaptureExtension.class)
class HarnessApprovalFlowTest {
    @Autowired ConversationService conversations;
    @Autowired ToolApprovalService approvals;
    @Autowired AgentRuntime runtime;
    @Autowired ModelConfigRepository models;
    @Autowired AgentDefinitionRepository agents;
    @Autowired ToolDefinitionRepository tools;
    @Autowired ToolGrantRepository grants;
    @Autowired ToolCallRepository calls;
    @Autowired RunRepository runs;
    @Autowired ConversationMessageRepository messages;
    @SpyBean RuntimeCheckpointRepository checkpoints;
    @Autowired ToolRegistry registry;
    @Autowired ToolInvocationGateway gateway;
    @Autowired SkillAccessGateway skills;
    @Autowired ModelCredentialCipher cipher;
    @Autowired ObjectMapper mapper;
    @Autowired ObjectProvider<JdbcClient> jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired org.springframework.context.ApplicationContext context;
    @SpyBean AuditAppender audit;
    protected PrincipalRef owner;
    protected UUID agent;
    protected UUID conversation;
    private ToolDefinition tool;
    /** 回环服务和执行池由每个用例独占并在结束时关闭。 */
    private HttpServer server;
    private ExecutorService executor;
    private final Queue<String> responses = new ConcurrentLinkedQueue<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger toolCalls = new AtomicInteger();
    /** 许可释放前不返回，模拟停止信号无法强制结束的实际工具。 */
    private CountDownLatch entered;
    private CountDownLatch release;
    private volatile boolean blocking;

    @BeforeEach
    void prepare() throws Exception {
        reset(audit);
        reset(checkpoints);
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        owner = new PrincipalRef(UUID.randomUUID(), "harness-owner", "Harness 验收主体",
                Set.of("agent:read", "agent:run", "agent:approve"));
        if (jdbc.getIfAvailable() != null) {
            // JDBC 子类只向当前 Testcontainers 临时库补充外键租户，不能读取部署者数据。
            jdbc.getObject().sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,:code,'Harness 验收租户',true,:now)")
                    .param("id", owner.tenantId().toString()).param("code", owner.tenantId().toString())
                    .param("now", Timestamp.from(Instant.now())).update();
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = responses.poll();
            // 实际模型启用 stream=true；使用 AgentScope 2.0.2 已验证可消费的 SSE 包装。
            byte[] bytes = (response == null ? "缺少回环测试响应" : "data: " + response + "\n\ndata: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response == null ? "application/json" : "text/event-stream; charset=UTF-8");
            exchange.sendResponseHeaders(response == null ? 500 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var model = models.save(new ModelConfig(UUID.randomUUID(), owner.tenantId(), ModelProviderType.OPENAI_COMPATIBLE,
                "回环模型", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture", true));
        tool = tools.save(new ToolDefinition(UUID.randomUUID(), owner.tenantId(), "echo", "受控验收工具", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}",
                ToolRiskLevel.HIGH, true, "", owner.principalId(), owner.principalId()));
        registry.register(tool, request -> {
            toolCalls.incrementAndGet();
            entered.countDown();
            if (blocking) {
                // 有界等待仅为测试防挂死；忽略线程中断仍保留在途许可，验证不能提前交付 CANCELLED。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (release.getCount() > 0 && System.nanoTime() < deadline) {
                    try { release.await(50, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { /* 测试工具故意不响应线程中断。 */ }
                }
                if (release.getCount() > 0) throw new IllegalStateException("测试未释放工具");
            }
            return ToolExecutionResult.succeeded("工具已完成", null);
        });
        agent = agents.save(new AgentDefinition(UUID.randomUUID(), owner.tenantId(), "Harness 合同", "", "", model.id(),
                "fixture", 0.1, 8, true, List.of(), owner.principalId(), owner.principalId())).id();
        grants.save(new ToolGrant(owner.tenantId(), tool.id(), agent, "", true));
        conversation = conversations.create(owner, agent).id();
        assertThat(runtime).isInstanceOf(AgentScopeRuntimeAdapter.class);
    }

    @AfterEach
    void close() {
        release.countDown();
        reset(audit);
        reset(checkpoints);
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void 审批跨Store和Runtime重建恢复原Run且历史与工具不重复() {
        var pending = waiting();
        String user = user();
        var state = checkpoints.find(owner.tenantId(), user, pending.runId().toString(), "agent_state").orElseThrow();
        assertThat(state.encryptedPayload()).startsWith("v1:").doesNotContain("唯一用户输入", "harness-call-1");
        assertThat(checkpoints.exists(UUID.randomUUID(), user, pending.runId().toString())).isFalse();
        var store = new RepositoryAgentStateStore(checkpoints, cipher, mapper, Clock.systemUTC(), Duration.ofMinutes(15));
        var rebuilt = AgentScopeRuntimeAdapter.create((tenant, model) -> new ModelCredential("invalid-loopback-key"), gateway,
                new AgentScopeRuntimeOptions(Duration.ofSeconds(10), Duration.ofSeconds(10), 1, true),
                Clock.systemUTC(), store, skills, AgentScopeExecutionMode.HARNESS);
        // 用新的生产 Runtime 接入原编排，状态来自重新创建的加密 Store，而非旧 Harness 对象。
        responses.add(finalResponse());
        var execution = new RunExecutionService(rebuilt, agents, models, tools, grants,
                context.getBean(com.cmagent.core.security.ToolAuthorizationPolicy.class),
                context.getBean(RunPersistenceService.class), context.getBean(com.cmagent.server.security.SensitiveDataRedactor.class),
                context.getBean(com.cmagent.server.diagnostic.ErrorDiagnosticLogger.class), context.getBean(SkillRuntimeService.class));
        var rebuiltApprovals = new ToolApprovalService(context.getBean(ToolApprovalRepository.class), runs, messages, checkpoints,
                context.getBean(RunPersistenceService.class), execution,
                context.getBean(com.cmagent.core.security.PermissionEvaluator.class), audit,
                context.getBean(com.cmagent.server.security.SensitiveDataRedactor.class),
                context.getBean(com.cmagent.server.config.AgentScopeRuntimeProperties.class),
                context.getBeanProvider(org.springframework.transaction.support.TransactionTemplate.class).getIfAvailable(),
                context.getBean(SkillTrialRepository.class), context.getBean(com.cmagent.server.service.SkillUnitOfWork.class));
        var result = rebuiltApprovals.decideAndResume(owner, agent, conversation, pending.approvalId(), pending.version(),
                items(pending, ToolApprovalDecision.APPROVE), ignored -> {}, ignored -> {}, ignored -> {});
        assertThat(result.run().runId()).isEqualTo(pending.runId());
        assertThat(result.run().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(toolCalls).hasValue(1);
        assertThat(checkpoints.exists(owner.tenantId(), user, pending.runId().toString())).isFalse();
        assertThat(requests).hasSize(2);
        assertThat(count(requests.getLast(), "唯一用户输入")).isEqualTo(1);
        assertThat(messages.listByRun(owner.tenantId(), conversation, pending.runId()))
                .extracting(ConversationMessage::role).containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        assertThat(execution.controls().find(pending.runId())).isNull();
    }

    @Test
    void 同一Run多轮审批每次只执行一次并保留只读历史() {
        var first = waiting();
        responses.add(toolResponse("harness-call-2"));
        var again = decide(first, ToolApprovalDecision.APPROVE);
        assertThat(again.run().status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(again.nextApproval().runId()).isEqualTo(first.runId());
        assertThat(again.nextApproval().approvalId()).isNotEqualTo(first.approvalId());
        responses.add(finalResponse());
        var done = decide(again.nextApproval(), ToolApprovalDecision.APPROVE);
        assertThat(done.run().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(toolCalls).hasValue(2);
        assertThat(messages.listByRun(owner.tenantId(), conversation, first.runId()))
                .extracting(ConversationMessage::role).containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        assertThat(approvals.listHistory(owner, agent, conversation, new ToolApprovalHistoryPageRequest(20, null, null)))
                .hasSize(2).allMatch(row -> !row.canDecide());
        assertThatThrownBy(() -> decide(first, ToolApprovalDecision.APPROVE))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(toolCalls).hasValue(2);
        assertNoCheckpoint(first);
    }

    @Test
    void 拒绝审批不调用工具并清除状态() {
        var pending = waiting();
        assertThat(decide(pending, ToolApprovalDecision.DENY).run().status()).isEqualTo(RunStatus.DENIED);
        assertThat(toolCalls).hasValue(0);
        assertNoCheckpoint(pending);
    }

    @Test
    void 审批前撤销工具授权恢复失败且状态不可重放() {
        var pending = waiting();
        grants.delete(owner.tenantId(), agent, tool.id());
        assertThatThrownBy(() -> decide(pending, ToolApprovalDecision.APPROVE)).isInstanceOf(RuntimeException.class);
        assertThat(runs.findByTenantAndAgentAndId(owner.tenantId(), agent, pending.runId()).orElseThrow().status())
                .isEqualTo(RunStatus.FAILED);
        assertThat(toolCalls).hasValue(0);
        assertNoCheckpoint(pending);
    }

    @Test
    void 首次审批创建审计失败仍关闭Run并删除已经保存的状态() {
        responses.add(toolResponse("harness-call-1"));
        doThrow(new AuditPersistenceException("审计不可用", new IllegalStateException("合成失败")))
                .when(audit).append(any(), any(), eq("TOOL_APPROVAL_CREATE"), any(), any(), any(), any());
        var run = new java.util.concurrent.atomic.AtomicReference<UUID>();
        assertThatThrownBy(() -> conversations.send(owner, agent, conversation, "唯一用户输入",
                started -> run.set(started.runId()), ignored -> {}, ignored -> {})).isInstanceOf(AuditPersistenceException.class);
        assertThat(runs.findByTenantAndAgentAndId(owner.tenantId(), agent, run.get()).orElseThrow().status()).isEqualTo(RunStatus.FAILED);
        assertThat(checkpoints.exists(owner.tenantId(), user(), run.get().toString())).isFalse();
        assertThat(toolCalls).hasValue(0);
    }

    @Test
    void 审批决定通知失败不执行工具并收口Run与状态() {
        var pending = waiting();
        assertThatThrownBy(() -> approvals.decideAndResume(owner, agent, conversation, pending.approvalId(), pending.version(),
                items(pending, ToolApprovalDecision.APPROVE), ignored -> { throw new IllegalStateException("通知失败"); },
                ignored -> {}, ignored -> {})).hasMessage("通知失败");
        assertThat(runs.findByTenantAndAgentAndId(owner.tenantId(), agent, pending.runId()).orElseThrow().status()).isEqualTo(RunStatus.FAILED);
        assertNoCheckpoint(pending);
        assertThat(toolCalls).hasValue(0);
    }

    @Test
    void 等待审批停止终态清理且旧决定不能恢复() throws Exception {
        var pending = waiting();
        mvc.perform(post(base() + "/runs/" + pending.runId() + "/cancel").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(approvals.get(owner, agent, conversation, pending.approvalId()).status()).isEqualTo(ToolApprovalStatus.CANCELLED);
        assertThatThrownBy(() -> decide(pending, ToolApprovalDecision.APPROVE)).isInstanceOf(RuntimeException.class);
        assertNoCheckpoint(pending);
        assertThat(toolCalls).hasValue(0);
    }

    @Test
    void 恢复中停止必须等待实际工具退出才交付终态() throws Exception {
        var pending = waiting();
        blocking = true;
        responses.add(finalResponse());
        var task = CompletableFuture.supplyAsync(() -> decide(pending, ToolApprovalDecision.APPROVE));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            mvc.perform(post(base() + "/runs/" + pending.runId() + "/cancel").header("Authorization", token(owner)))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CANCELLING"))
                    .andExpect(jsonPath("$.finishedAt").doesNotExist());
            assertThat(task.isDone()).isFalse();
            release.countDown();
            assertThat(task.get(8, TimeUnit.SECONDS).run().status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(toolCalls).hasValue(1);
            assertNoCheckpoint(pending);
            mvc.perform(post(base() + "/runs/" + pending.runId() + "/cancel").header("Authorization", token(owner)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        } finally { release.countDown(); task.get(8, TimeUnit.SECONDS); }
    }

    @Test
    void 到期扫描使用原主体状态槽且旧审批不再恢复() {
        var pending = waiting();
        var row = context.getBean(ToolApprovalRepository.class)
                .find(owner.tenantId(), agent, conversation, pending.approvalId()).orElseThrow();
        // 合成扫描截止时间覆盖到期条件，不修改主机时钟，也不等待生产 TTL。
        assertThat(approvals.expireFromSystem(row, row.expiresAt().plusSeconds(1))).isTrue();
        assertThat(approvals.get(owner, agent, conversation, pending.approvalId()).status()).isEqualTo(ToolApprovalStatus.EXPIRED);
        assertThat(runs.findByTenantAndAgentAndId(owner.tenantId(), agent, pending.runId()).orElseThrow().status()).isEqualTo(RunStatus.DENIED);
        assertNoCheckpoint(pending);
        assertThatThrownBy(() -> decide(pending, ToolApprovalDecision.APPROVE)).isInstanceOf(RuntimeException.class);
        assertThat(toolCalls).hasValue(0);
    }

    @Test
    void 并发重复决定只接受一次且不会重复执行工具() throws Exception {
        var pending = waiting();
        blocking = true;
        responses.add(finalResponse());
        var first = CompletableFuture.supplyAsync(() -> decide(pending, ToolApprovalDecision.APPROVE));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> decide(pending, ToolApprovalDecision.APPROVE))
                    .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                            failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));
            release.countDown();
            assertThat(first.get(8, TimeUnit.SECONDS).run().status()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(toolCalls).hasValue(1);
            assertNoCheckpoint(pending);
        } finally { release.countDown(); first.get(8, TimeUnit.SECONDS); }
    }

    @Test
    void 停止后工具完成审计失败保持严格异常并收口为失败() throws Exception {
        var pending = waiting();
        blocking = true;
        doThrow(new AuditPersistenceException("审计不可用", new IllegalStateException("apiKey=synthetic-private-marker http://internal.invalid/path")))
                .when(audit).append(any(), any(), eq("TOOL_CALL_COMPLETED"), any(), any(), any(), any());
        var task = CompletableFuture.supplyAsync(() -> decide(pending, ToolApprovalDecision.APPROVE));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            conversations.cancel(owner, agent, conversation, pending.runId());
            release.countDown();
            assertThatThrownBy(() -> task.get(8, TimeUnit.SECONDS)).hasCauseInstanceOf(AuditPersistenceException.class);
            assertThat(runs.findByTenantAndAgentAndId(owner.tenantId(), agent, pending.runId()).orElseThrow().status()).isEqualTo(RunStatus.FAILED);
            assertNoCheckpoint(pending);
        } finally { release.countDown(); }
    }

    @Test
    void Web撤权恢复失败先通知决定再返回关联且脱敏的错误(CapturedOutput output) throws Exception {
        var pending = waiting();
        grants.delete(owner.tenantId(), agent, tool.id());
        String body = mapper.writeValueAsString(Map.of("expectedVersion", pending.version(), "decisions", items(pending, ToolApprovalDecision.APPROVE)));
        String errorId = "harness-restore-" + UUID.randomUUID();
        var response = mvc.perform(post(base() + "/approvals/" + pending.approvalId() + "/decision/stream")
                        .header("Authorization", token(owner)).header("X-Request-Id", errorId)
                        .contentType("application/json").content(body)).andExpect(request().asyncStarted()).andReturn();
        response.getAsyncResult(10_000);
        String events = mvc.perform(asyncDispatch(response)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(events).contains("event:approval-decision", "event:error", errorId)
                .doesNotContain("invalid-loopback-key", "java.lang", "127.0.0.1");
        assertThat(events.indexOf("event:approval-decision")).isLessThan(events.indexOf("event:error"));
        assertThat(output.getAll()).contains("errorId=" + errorId, "errorCode=RUNTIME_EXECUTION_FAILED");
        assertNoCheckpoint(pending);
        assertThat(toolCalls).hasValue(0);
    }

    @Test
    void Web停止越权与跨租户均拒绝且保持待审批状态() throws Exception {
        var pending = waiting();
        var other = new PrincipalRef(owner.tenantId(), "other", "其他主体", owner.permissions());
        mvc.perform(post(base() + "/runs/" + pending.runId() + "/cancel").header("Authorization", token(other)))
                .andExpect(status().isForbidden());
        var cross = new PrincipalRef(UUID.randomUUID(), owner.principalId(), "另一租户", owner.permissions());
        mvc.perform(post(base() + "/runs/" + pending.runId() + "/cancel").header("Authorization", token(cross)))
                .andExpect(status().isNotFound());
        assertThat(approvals.get(owner, agent, conversation, pending.approvalId()).status()).isEqualTo(ToolApprovalStatus.PENDING);
        assertThat(checkpoints.exists(owner.tenantId(), user(), pending.runId().toString())).isTrue();
        assertThat(toolCalls).hasValue(0);
        decide(pending, ToolApprovalDecision.DENY);
    }

    @Test
    void 独立TEST保留非交互HIGH拒绝合同且不创建会话消息或隐式审批() {
        responses.add(toolResponse("harness-test-call"));
        var run = context.getBean(RunPersistenceService.class).start(owner, agent, "独立 TEST 输入", UUID.randomUUID(), RunKind.TEST);
        var result = context.getBean(RunExecutionService.class).runPrepared(owner, agent, run, run.input(), null, ignored -> {});
        assertThat(result.run().status()).isEqualTo(RunStatus.DENIED);
        assertThat(approvals.currentPendingForRun(owner, agent, run.id())).isNull();
        assertThat(messages.listByRun(owner.tenantId(), conversation, run.id())).isEmpty();
        assertThat(checkpoints.exists(owner.tenantId(), user(), run.id().toString())).isFalse();
        assertThat(toolCalls).hasValue(0);
    }

    @Test
    void 接受决定后停止抢先于恢复时不执行任何待调用工具() throws Exception {
        var pending = waiting();
        var notified = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var task = CompletableFuture.supplyAsync(() -> approvals.decideAndResume(owner, agent, conversation,
                pending.approvalId(), pending.version(), items(pending, ToolApprovalDecision.APPROVE), ignored -> {
                    notified.countDown();
                    try { if (!proceed.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放决定通知"); }
                    catch (InterruptedException failure) { throw new IllegalStateException("通知等待被中断", failure); }
                }, ignored -> {}, ignored -> {}));
        try {
            assertThat(notified.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(conversations.cancel(owner, agent, conversation, pending.runId()).status()).isEqualTo(RunStatus.CANCELLING);
            proceed.countDown();
            assertThat(task.get(8, TimeUnit.SECONDS).run().status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(toolCalls).hasValue(0);
            assertNoCheckpoint(pending);
        } finally { proceed.countDown(); task.get(8, TimeUnit.SECONDS); }
    }

    @Test
    void 检查点读取数据库故障保持严格异常且不执行工具() {
        var pending = waiting();
        var failure = new org.springframework.dao.DataAccessResourceFailureException("合成检查点读取故障");
        doThrow(failure).when(checkpoints).find(owner.tenantId(), user(), pending.runId().toString(), "agent_state");
        assertThatThrownBy(() -> decide(pending, ToolApprovalDecision.APPROVE)).isSameAs(failure);
        assertThat(runs.findByTenantAndAgentAndId(owner.tenantId(), agent, pending.runId()).orElseThrow().status()).isEqualTo(RunStatus.FAILED);
        assertThat(toolCalls).hasValue(0);
        assertNoCheckpoint(pending);
    }

    @Test
    void 加密状态损坏恢复失败不回退新执行且删除损坏槽() {
        var pending = waiting();
        var old = checkpoints.find(owner.tenantId(), user(), pending.runId().toString(), "agent_state").orElseThrow();
        checkpoints.save(new RuntimeCheckpoint(old.id(), old.tenantId(), old.userId(), old.sessionId(), old.stateKey(),
                old.stateType(), old.listPayload(), "v1:invalid-ciphertext", old.expiresAt(), old.createdAt(), Instant.now()));
        assertThatThrownBy(() -> decide(pending, ToolApprovalDecision.APPROVE)).isInstanceOf(RunExecutionService.RuntimeExecutionException.class);
        assertThat(runs.findByTenantAndAgentAndId(owner.tenantId(), agent, pending.runId()).orElseThrow().status()).isEqualTo(RunStatus.FAILED);
        assertThat(toolCalls).hasValue(0);
        assertThat(requests).hasSize(1);
        assertNoCheckpoint(pending);
    }

    @Test
    void 到期与批准竞争只有一方生效且不存在工具重放() throws Exception {
        var pending = waiting();
        var row = context.getBean(ToolApprovalRepository.class)
                .find(owner.tenantId(), agent, conversation, pending.approvalId()).orElseThrow();
        responses.add(finalResponse());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var expired = pool.submit(() -> { start.await(); return approvals.expireFromSystem(row, row.expiresAt().plusSeconds(1)); });
            var decided = pool.submit(() -> {
                start.await();
                try { return decide(pending, ToolApprovalDecision.APPROVE).run().status(); }
                catch (org.springframework.web.server.ResponseStatusException conflict) {
                    assertThat(conflict.getStatusCode().value()).isEqualTo(409);
                    return null;
                }
            });
            start.countDown();
            boolean wonExpiry = expired.get(10, TimeUnit.SECONDS);
            assertThat(decided.get(10, TimeUnit.SECONDS)).isEqualTo(wonExpiry ? null : RunStatus.SUCCEEDED);
            assertThat(runs.findByTenantAndAgentAndId(owner.tenantId(), agent, pending.runId()).orElseThrow().status())
                    .isEqualTo(wonExpiry ? RunStatus.DENIED : RunStatus.SUCCEEDED);
            assertThat(toolCalls).hasValue(wonExpiry ? 0 : 1);
            assertNoCheckpoint(pending);
        }
    }

    @Test
    void 部分批准仅执行允许项并保留逐项拒绝决定() throws Exception {
        var response = mapper.readTree(toolResponse("allowed-call"));
        var toolItems = (com.fasterxml.jackson.databind.node.ArrayNode) response.path("choices").get(0).path("message").path("tool_calls");
        var denied = toolItems.get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) denied).put("id", "denied-call");
        toolItems.add(denied);
        responses.add(mapper.writeValueAsString(response));
        var started = conversations.send(owner, agent, conversation, "两项工具审批");
        assertThat(started.run().status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        var pending = approvals.listPending(owner, agent, conversation).getFirst();
        assertThat(pending.items()).hasSize(2);
        responses.add(finalResponse());
        var decisions = pending.items().stream().map(item -> new ToolApprovalService.ItemDecision(item.itemId(),
                "allowed-call".equals(item.toolCallId()) ? ToolApprovalDecision.APPROVE : ToolApprovalDecision.DENY)).toList();
        var result = approvals.decideAndResume(owner, agent, conversation, pending.approvalId(), pending.version(), decisions,
                ignored -> {}, ignored -> {}, ignored -> {});
        assertThat(result.decision().status()).isEqualTo(ToolApprovalStatus.PARTIALLY_APPROVED);
        // 用户逐项拒绝保留审批事实；允许项执行并由模型完成时保持原有成功合同。
        // 执行前的工具授权拒绝仍由治理网关映射为 DENIED，不能与部分批准混为一谈。
        assertThat(result.run().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.decision().items()).filteredOn(item -> "denied-call".equals(item.toolCallId()))
                .singleElement().extracting(ToolApprovalService.ToolApprovalItemView::decision).isEqualTo(ToolApprovalDecision.DENY);
        assertThat(toolCalls).hasValue(1);
        assertNoCheckpoint(pending);
    }

    protected ToolApprovalService.ToolApprovalView waiting() {
        responses.add(toolResponse("harness-call-1"));
        var result = conversations.send(owner, agent, conversation, "唯一用户输入");
        assertThat(result.run().status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(toolCalls).hasValue(0);
        return approvals.listPending(owner, agent, conversation).getFirst();
    }
    protected ToolApprovalService.ApprovalResumeOutcome decide(ToolApprovalService.ToolApprovalView pending, ToolApprovalDecision decision) {
        return approvals.decideAndResume(owner, agent, conversation, pending.approvalId(), pending.version(),
                items(pending, decision), ignored -> {}, ignored -> {}, ignored -> {});
    }
    private List<ToolApprovalService.ItemDecision> items(ToolApprovalService.ToolApprovalView pending, ToolApprovalDecision decision) {
        return pending.items().stream().map(item -> new ToolApprovalService.ItemDecision(item.itemId(), decision)).toList();
    }
    protected void assertNoCheckpoint(ToolApprovalService.ToolApprovalView pending) {
        assertThat(checkpoints.exists(owner.tenantId(), user(), pending.runId().toString())).isFalse();
    }
    private String user() { return owner.tenantId() + ":" + owner.principalId(); }
    private String base() { return "/api/agents/" + agent + "/conversations/" + conversation; }
    private String token(PrincipalRef actor) { return "Bearer " + jwt.createToken(actor.tenantId(), actor.principalId(), actor.displayName(), List.copyOf(actor.permissions())); }
    private static int count(String text, String value) { return (text.length() - text.replace(value, "").length()) / value.length(); }
    private String toolResponse(String call) {
        return "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"" + call + "\",\"type\":\"function\",\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"value\\\":\\\"hello\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
    }
    private static String finalResponse() {
        return "{\"id\":\"fixture-final\",\"object\":\"chat.completion\",\"created\":2,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Harness 最终回答\"},\"finish_reason\":\"stop\"}]}";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        /** 不可用的占位 Key 仅发送到当前回环服务，不读取或持久化模型真实凭据。 */
        @Bean ModelCredentialProvider fixtureCredentials() { return (tenant, model) -> new ModelCredential("invalid-loopback-key"); }
    }

}
