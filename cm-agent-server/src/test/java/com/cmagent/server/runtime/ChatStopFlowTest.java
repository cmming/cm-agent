package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.security.JwtService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实会话、权限、审计和 memory 仓储；仅替换模型，不能证明外部 Provider 已断连。 */
@SpringBootTest(classes = CmAgentServerApplication.class, properties = {
        "cm-agent.approval-expiry.enabled=false", "cm-agent.agentscope.studio.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ChatStopFlowTest.Configuration.class)
@ExtendWith(OutputCaptureExtension.class)
class ChatStopFlowTest {
    @Autowired ConversationService service;
    @Autowired ToolApprovalService approvals;
    @Autowired AgentDefinitionRepository agents;
    @Autowired ModelConfigRepository models;
    @Autowired RunRepository runs;
    @Autowired ConversationMessageRepository messages;
    @Autowired RuntimeCheckpointRepository checkpoints;
    @Autowired BlockingRuntime runtime;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @SpyBean AuditAppender audit;
    private PrincipalRef owner;
    private UUID agent;
    private UUID conversation;

    @BeforeEach
    void prepare() {
        reset(audit);
        runtime.reset();
        owner = new PrincipalRef(UUID.randomUUID(), "stop-owner", "停止验收主体", Set.of("agent:read", "agent:run"));
        var model = models.save(new ModelConfig(UUID.randomUUID(), owner.tenantId(), ModelProviderType.OPENAI_COMPATIBLE,
                "受控模型", "https://example.invalid/v1", "controlled", true));
        agent = agents.save(new AgentDefinition(UUID.randomUUID(), owner.tenantId(), "停止验收", "", "", model.id(),
                "controlled", 0.1, 5, true, List.of(), owner.principalId(), owner.principalId())).id();
        conversation = service.create(owner, agent).id();
    }

    @AfterEach
    void release() { runtime.release.countDown(); }

    @Test
    void 停止中禁止新消息重复停止幂等实际退出后保留正文和中断历史() throws Exception {
        var result = CompletableFuture.supplyAsync(() -> service.send(owner, agent, conversation, "开始阻塞运行"));
        try {
            assertThat(runtime.entered.await(3, TimeUnit.SECONDS)).isTrue();
            UUID id = runtime.request.runId();
            mvc.perform(post(base() + "/runs/" + id + "/cancel").header("Authorization", token(owner)))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CANCELLING"))
                    .andExpect(jsonPath("$.finishedAt").doesNotExist());
            mvc.perform(post(base() + "/runs/" + id + "/cancel").header("Authorization", token(owner)))
                    .andExpect(status().isAccepted());
            assertThat(result.isDone()).isFalse();
            assertThatThrownBy(() -> service.send(owner, agent, conversation, "不允许重叠"))
                    .isInstanceOf(RunControlException.class);
            mvc.perform(get(base() + "/active-run").header("Authorization", token(owner)))
                    .andExpect(jsonPath("$.status").value("CANCELLING"));
            runtime.release.countDown();
            var stopped = result.get(4, TimeUnit.SECONDS);
            assertThat(stopped.run().status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(stopped.run().output()).contains("已输出正文").doesNotContain("private-stop-marker", "internal.invalid", "迟到输出");
            assertThat(stopped.assistantMessage().contentBlocks()).allMatch(block -> block.type() == MessageContentType.TEXT);
            mvc.perform(post(base() + "/runs/" + id + "/cancel").header("Authorization", token(owner)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
            mvc.perform(get(base() + "/messages").header("Authorization", token(owner)))
                    .andExpect(jsonPath("$.runStatuses['" + id + "']").value("CANCELLED"));
            mvc.perform(get(base() + "/active-run").header("Authorization", token(owner))).andExpect(status().isNoContent());
            runtime.quick = true;
            assertThat(service.send(owner, agent, conversation, "新一轮").run().status()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(runtime.request.input()).contains("本轮已由用户停止，回答可能不完整", "新一轮");
        } finally { runtime.release.countDown(); result.get(4, TimeUnit.SECONDS); }
    }

    @Test
    void 等待审批停止删除检查点且旧批准不能恢复() throws Exception {
        var waiting = seed(RunStatus.WAITING_APPROVAL);
        var pending = approvals.create(owner, agent, conversation, waiting.id(), new RuntimePendingApproval("reply", List.of(
                new RuntimePendingApproval.RuntimePendingToolCall("pending-call", UUID.randomUUID(), "待审批工具",
                        ToolRiskLevel.HIGH, "安全摘要", "a".repeat(64)))));
        String user = owner.tenantId() + ":" + owner.principalId();
        Instant now = Instant.now();
        checkpoints.save(new RuntimeCheckpoint(UUID.randomUUID(), owner.tenantId(), user, waiting.id().toString(),
                "state", "fixture", false, "不可解密的测试占位密文", now.plusSeconds(60), now, now));
        mvc.perform(post(base() + "/runs/" + waiting.id() + "/cancel").header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        var cancelled = approvals.get(owner, agent, conversation, pending.approvalId());
        assertThat(cancelled.status()).isEqualTo(ToolApprovalStatus.CANCELLED);
        assertThat(cancelled.version()).isEqualTo(pending.version() + 1);
        assertThat(cancelled.canDecide()).isFalse();
        assertThat(checkpoints.exists(owner.tenantId(), user, waiting.id().toString())).isFalse();
        assertThatThrownBy(() -> approvals.decideAndResume(owner, agent, conversation, pending.approvalId(), pending.version(),
                List.of(new ToolApprovalService.ItemDecision(pending.items().getFirst().itemId(), ToolApprovalDecision.APPROVE)),
                ignored -> {}, ignored -> {}, ignored -> {})).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(runtime.request).isNull();
    }

    @Test
    void 越权跨租户错会话和孤儿运行停止均失败关闭(CapturedOutput output) throws Exception {
        var orphan = seed(RunStatus.RUNNING);
        var other = new PrincipalRef(owner.tenantId(), "other", "其他主体", owner.permissions());
        mvc.perform(post(base() + "/runs/" + orphan.id() + "/cancel").header("Authorization", token(other)))
                .andExpect(status().isForbidden());
        var readonly = new PrincipalRef(owner.tenantId(), owner.principalId(), "只读", Set.of("agent:read"));
        mvc.perform(post(base() + "/runs/" + orphan.id() + "/cancel").header("Authorization", token(readonly)))
                .andExpect(status().isForbidden());
        var cross = new PrincipalRef(UUID.randomUUID(), owner.principalId(), "另一租户", owner.permissions());
        mvc.perform(get(base() + "/runs/" + orphan.id()).header("Authorization", token(cross))).andExpect(status().isNotFound());
        mvc.perform(get("/api/agents/" + agent + "/conversations/" + service.create(owner, agent).id() + "/runs/" + orphan.id())
                .header("Authorization", token(owner))).andExpect(status().isNotFound());
        var response = mvc.perform(post(base() + "/runs/" + orphan.id() + "/cancel").header("Authorization", token(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_CONTROL_UNAVAILABLE"))
                .andExpect(jsonPath("$.errorId").isNotEmpty()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("example.invalid", "java.lang", "private-stop-marker");
        String errorId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("errorId").asText();
        assertThat(output.getAll()).contains("errorId=" + errorId, "RUN_CONTROL_UNAVAILABLE", orphan.id().toString());
        assertThat(service.activeRun(owner, agent, conversation).status()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void 审计不可用不能发出停止信号() throws Exception {
        var result = CompletableFuture.supplyAsync(() -> service.send(owner, agent, conversation, "受控运行"));
        try {
            assertThat(runtime.entered.await(3, TimeUnit.SECONDS)).isTrue();
            doThrow(new AuditPersistenceException("审计不可用", new IllegalStateException("apiKey=private-stop-marker")))
                    .when(audit).append(any(), any(), eq("AGENT_RUN_CANCEL"), any(), any(), any(), any());
            assertThatThrownBy(() -> service.cancel(owner, agent, conversation, runtime.request.runId()))
                    .isInstanceOf(AuditPersistenceException.class);
            assertThat(runtime.request.cancellation().isRequested()).isFalse();
            assertThat(service.activeRun(owner, agent, conversation).status()).isEqualTo(RunStatus.RUNNING);
        } finally {
            reset(audit);
            runtime.release.countDown();
            result.get(4, TimeUnit.SECONDS);
        }
    }

    private RunRecord seed(RunStatus status) {
        var row = RunRecord.create(UUID.randomUUID(), owner.tenantId(), agent, owner.principalId(), "种子输入", Instant.now());
        runs.save(owner.tenantId(), row);
        if (status == RunStatus.WAITING_APPROVAL) row = runs.waitForApproval(owner.tenantId(), row.id());
        messages.append(owner.tenantId(), new ConversationMessageDraft(UUID.randomUUID(), owner.tenantId(), conversation,
                MessageRole.USER, "主体", List.of(MessageContentBlock.text("种子输入")), row.id(), Instant.now()));
        return row;
    }
    private String base() { return "/api/agents/" + agent + "/conversations/" + conversation; }
    private String token(PrincipalRef actor) { return "Bearer " + jwt.createToken(actor.tenantId(), actor.principalId(), actor.displayName(), List.copyOf(actor.permissions())); }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary BlockingRuntime blockingRuntime() { return new BlockingRuntime(); }
    }

    /** 真实调用由许可代表；释放前即使停止信号已发布也不返回终态，不调用外部系统。 */
    static final class BlockingRuntime implements AgentRuntime {
        private volatile AgentRunRequest request;
        private volatile boolean quick;
        private CountDownLatch entered;
        private CountDownLatch release;
        void reset() { request = null; quick = false; entered = new CountDownLatch(1); release = new CountDownLatch(1); }
        @Override public boolean supportsCancellation() { return true; }
        @Override public AgentRunResult run(AgentRunRequest request) { throw new UnsupportedOperationException("测试仅使用结构化入口"); }
        @Override public AgentRuntimeResult runStructured(AgentRunRequest request, Consumer<AgentTextDelta> delta, Consumer<AgentProgressEvent> progress) {
            this.request = request;
            if (!quick) {
                try (var permit = request.cancellation().enter()) {
                    delta.accept(new AgentTextDelta("reply", "text", "已输出正文 apiKey=private-stop-marker http://internal.invalid/private"));
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放受控调用");
                } catch (InterruptedException failure) { throw new IllegalStateException("测试调用被中断", failure); }
                delta.accept(new AgentTextDelta("reply", "text", "迟到输出"));
            }
            Instant now = Instant.now();
            return new AgentRuntimeResult(new AgentRunResult(request.runId(), RunStatus.SUCCEEDED, "正常完成", List.of(), now, now, ""), null);
        }
    }
}
