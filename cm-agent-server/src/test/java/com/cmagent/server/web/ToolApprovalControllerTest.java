package com.cmagent.server.web;

import com.cmagent.core.domain.ToolApprovalStatus;
import com.cmagent.core.domain.ToolApprovalHistoryPageRequest;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.runtime.ConversationService;
import com.cmagent.server.runtime.ToolApprovalService;
import com.cmagent.server.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 只隔离运行编排，不绕过真实 JWT、权限入口、校验器或统一错误映射；测试不连接外部 Studio。
@SpringBootTest(classes = CmAgentServerApplication.class, properties = "cm-agent.agentscope.studio.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ToolApprovalControllerTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID APPROVAL = UUID.randomUUID();
    private static final String PATH = "/api/agents/" + AGENT + "/conversations/" + CONVERSATION
            + "/approvals/" + APPROVAL + "/decision/stream";
    private static final String BODY = "{\"expectedVersion\":0,\"decisions\":[{\"itemId\":\""
            + UUID.randomUUID() + "\",\"decision\":\"APPROVE\"}]}";

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @MockBean private ToolApprovalService approvals;
    @MockBean private ConversationService conversations;
    @SpyBean private AuditAppender audit;
    @SpyBean private ErrorDiagnosticLogger diagnostics;

    @Test
    void 缺少审批权限或运行权限均拒绝并审计() throws Exception {
        for (List<String> permissions : List.of(List.of("agent:run"), List.of("agent:approve"))) {
            mvc.perform(post(PATH).header("Authorization", token(permissions))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        verifyNoInteractions(approvals);
        verify(audit, times(2)).accessDenied(any(), eq("TOOL_APPROVAL"), eq(APPROVAL.toString()), anyString(), anyString());
    }

    @Test
    void 过期及冲突在建立流之前返回稳定错误码() throws Exception {
        for (HttpStatus status : List.of(HttpStatus.CONFLICT, HttpStatus.GONE)) {
            doThrow(new ResponseStatusException(status, "审批状态不可提交"))
                    .when(approvals).validateForSubmission(any(), eq(AGENT), eq(CONVERSATION), eq(APPROVAL), eq(0L), anyList());
            mvc.perform(post(PATH).header("Authorization", token(List.of("agent:run", "agent:approve")))
                            .header("X-Request-Id", "approval-preflight")
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().is(status.value()))
                    .andExpect(jsonPath("$.code").value(status == HttpStatus.GONE
                            ? "TOOL_APPROVAL_EXPIRED" : "TOOL_APPROVAL_CONFLICT"))
                    .andExpect(jsonPath("$.errorId").value("approval-preflight"));
        }
    }

    @Test
    void 非法决定枚举及空决定集合返回400且不调用恢复() throws Exception {
        for (String body : List.of(BODY.replace("APPROVE", "ALLOW_ALWAYS"), "{\"expectedVersion\":0,\"decisions\":[]}")) {
            mvc.perform(post(PATH).header("Authorization", token(List.of("agent:run", "agent:approve")))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOOL_APPROVAL_INVALID_DECISION"));
        }
        verifyNoInteractions(approvals);
    }

    @Test
    void 决定接受后异常保持事件顺序并关联脱敏日志(CapturedOutput output) throws Exception {
        doAnswer(invocation -> {
            Consumer<ToolApprovalService.ToolApprovalView> accepted = invocation.getArgument(6);
            accepted.accept(new ToolApprovalService.ToolApprovalView(APPROVAL, AGENT, CONVERSATION,
                    UUID.randomUUID(), ToolApprovalStatus.APPROVED, 1, "发起人", "发起人", Instant.now(),
                    false, Instant.now().plusSeconds(900), List.of()));
            throw new IllegalStateException("apiKey=private-test-marker http://internal.invalid/secret");
        }).when(approvals).decideAndResume(any(), eq(AGENT), eq(CONVERSATION), eq(APPROVAL), eq(0L), anyList(), any(), any(), any());
        var result = mvc.perform(post(PATH).header("Authorization", token(List.of("agent:run", "agent:approve")))
                        .header("X-Request-Id", "approval-resume-error")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10_000);
        var completed = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();
        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event:approval-decision", "event:error", "INTERNAL_ERROR", "approval-resume-error")
                .doesNotContain("private-test-marker", "internal.invalid", "IllegalStateException");
        assertThat(body.indexOf("event:approval-decision")).isLessThan(body.indexOf("event:error"));
        verify(diagnostics).error(argThat(context -> context.errorId().equals("approval-resume-error")
                && context.tenantId().equals(TENANT.toString())), any(Throwable.class));
        assertThat(output.toString()).contains("errorId=approval-resume-error", "errorCode=INTERNAL_ERROR",
                        "tenantId=" + TENANT, "ToolApprovalControllerTest.java:")
                .doesNotContain("private-test-marker", "internal.invalid");
    }

    @Test
    void 流内权限拒绝返回脱敏原因并使用关联警告日志(CapturedOutput output) throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                "执行权限已撤销 apiKey=private-denial-marker http://internal.invalid/secret"))
                .when(approvals).decideAndResume(any(), eq(AGENT), eq(CONVERSATION), eq(APPROVAL),
                        eq(0L), anyList(), any(), any(), any());
        var result = mvc.perform(post(PATH).header("Authorization", token(List.of("agent:run", "agent:approve")))
                        .header("X-Request-Id", "approval-denial")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10_000);
        var completed = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();
        assertThat(completed.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("event:error", "FORBIDDEN", "执行权限已撤销", "approval-denial")
                .doesNotContain("private-denial-marker", "internal.invalid", "ResponseStatusException");
        assertThat(output.toString()).contains("WARN", "errorId=approval-denial", "errorCode=FORBIDDEN",
                        "tenantId=" + TENANT, "conversationId=" + CONVERSATION)
                .doesNotContain("private-denial-marker", "internal.invalid");
        verifyNoInteractions(diagnostics);
    }

    private String token(List<String> permissions) {
        return "Bearer " + jwt.createToken(TENANT, "initiator", "发起人", permissions);
    }

    private static final String HISTORY = "/api/agents/" + AGENT + "/conversations/" + CONVERSATION + "/approvals/history";

    @Test
    void 历史只需读取权限且分页返回多个只读决定() throws Exception {
        Instant time = Instant.parse("2026-09-03T00:00:00Z");
        var first = historyView(UUID.fromString("f0000000-0000-0000-0000-000000000001"), time);
        var second = historyView(UUID.fromString("10000000-0000-0000-0000-000000000001"), time);
        when(approvals.listHistory(any(), eq(AGENT), eq(CONVERSATION), eq(new ToolApprovalHistoryPageRequest(1, null, null))))
                .thenReturn(List.of(first));
        when(approvals.listHistory(any(), eq(AGENT), eq(CONVERSATION), eq(new ToolApprovalHistoryPageRequest(1, time, first.approvalId()))))
                .thenReturn(List.of(second));
        var result = mvc.perform(get(HISTORY).param("limit", "1").header("Authorization", token(List.of("agent:read"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].approvalId").value(first.approvalId().toString()))
                .andExpect(jsonPath("$.items[0].canDecide").value(false))
                .andExpect(jsonPath("$.items[0].checkpointRef").doesNotExist())
                .andExpect(jsonPath("$.items[0].tenantId").doesNotExist()).andReturn();
        String cursor = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("nextCursor").asText();
        mvc.perform(get(HISTORY).param("limit", "1").param("cursor", cursor).header("Authorization", token(List.of("agent:read"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].approvalId").value(second.approvalId().toString()))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        verify(approvals, never()).decideAndResume(any(), any(), any(), any(), anyLong(), anyList(), any(), any(), any());
    }

    @Test
    void 历史缺少读取权限拒绝并审计且不执行查询() throws Exception {
        mvc.perform(get(HISTORY)).andExpect(status().isUnauthorized());
        mvc.perform(get(HISTORY).header("Authorization", token(List.of("agent:approve"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(approvals);
        verify(audit).accessDenied(any(), eq("CONVERSATION"), eq(CONVERSATION.toString()), eq("agent:read"), anyString());
    }

    @Test
    void 历史非法及跨会话游标返回分页错误而非决定错误() throws Exception {
        String foreign = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("1|" + AGENT + "|" + UUID.randomUUID() + "|2026-09-03T00:00:00Z|" + APPROVAL).getBytes(StandardCharsets.UTF_8));
        for (String cursor : List.of("bad", "x".repeat(257), foreign)) {
            mvc.perform(get(HISTORY).param("cursor", cursor).header("Authorization", token(List.of("agent:read")))
                            .header("X-Request-Id", "history-validation"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errorId").value("history-validation"));
        }
        mvc.perform(get(HISTORY).param("limit", "101").header("Authorization", token(List.of("agent:read"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(approvals);
    }

    @Test
    void 不可见会话不查询历史且故障关联可信上下文(CapturedOutput output) throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "不可访问"))
                .when(conversations).get(any(), eq(AGENT), eq(CONVERSATION));
        mvc.perform(get(HISTORY).header("Authorization", token(List.of("agent:read"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("会话或 Agent 不存在"));
        verifyNoInteractions(approvals);
        reset(conversations);
        when(approvals.listHistory(any(), any(), any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("apiKey=history-secret http://internal.invalid/db"));
        var result = mvc.perform(get(HISTORY).header("Authorization", token(List.of("agent:read")))
                        .header("X-Request-Id", "history-load-error"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PERSISTENCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.errorId").value("history-load-error")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("history-secret", "internal.invalid", "Exception");
        verify(diagnostics).error(argThat(context -> context.errorId().equals("history-load-error")
                && context.tenantId().equals(TENANT.toString()) && context.source().equals("CONVERSATION:" + CONVERSATION)), any(Throwable.class));
        assertThat(output.toString()).contains("history-load-error", "PERSISTENCE_UNAVAILABLE", "ToolApprovalControllerTest.java:")
                .doesNotContain("history-secret", "internal.invalid");
    }

    private static ToolApprovalService.ToolApprovalView historyView(UUID approvalId, Instant time) {
        return new ToolApprovalService.ToolApprovalView(approvalId, AGENT, CONVERSATION, UUID.randomUUID(),
                ToolApprovalStatus.APPROVED, 1, "发起人", "审批人", time, false, time.plusSeconds(900), List.of());
    }
}
