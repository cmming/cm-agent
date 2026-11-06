package com.cmagent.server.web;

import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.OperationAuthorizationMode;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.runtime.ConversationService;
import com.cmagent.server.runtime.ToolApprovalService;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class ConversationControllerProjectModeTest {
    @Test
    void 项目消息体中的授权方式原样交给可信服务校验() {
        ConversationService conversations = mock(ConversationService.class);
        PermissionEvaluator permissions = mock(PermissionEvaluator.class);
        when(permissions.check(any(), any())).thenReturn(AuthorizationDecision.allow());
        var controller = new ConversationController(conversations, permissions, mock(AuditAppender.class),
                mock(TaskExecutor.class), mock(ErrorDiagnosticLogger.class), mock(ToolApprovalService.class),
                new SensitiveDataRedactor());
        UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), conversation = UUID.randomUUID();
        var session = new JwtService.JwtSession(tenant, "Owner", "测试", List.of("agent:run"));
        var authentication = new UsernamePasswordAuthenticationToken(session, null, List.of());

        controller.send(agent, conversation, new ConversationController.SendMessageRequest(
                "修改文件", null, OperationAuthorizationMode.FULL_ACCESS), authentication);

        verify(conversations).send(any(), eq(agent), eq(conversation), eq("修改文件"),
                isNull(), eq(OperationAuthorizationMode.FULL_ACCESS));
    }

    @Test
    void 审批恢复只读端点从原Run快照返回授权方式() {
        ConversationService conversations = mock(ConversationService.class);
        PermissionEvaluator permissions = mock(PermissionEvaluator.class);
        when(permissions.check(any(), any())).thenReturn(AuthorizationDecision.allow());
        var controller = new ConversationController(conversations, permissions, mock(AuditAppender.class),
                mock(TaskExecutor.class), mock(ErrorDiagnosticLogger.class), mock(ToolApprovalService.class),
                new SensitiveDataRedactor());
        UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), conversation = UUID.randomUUID(), run = UUID.randomUUID();
        var session = new JwtService.JwtSession(tenant, "Owner", "测试", List.of("agent:read"));
        var authentication = new UsernamePasswordAuthenticationToken(session, null, List.of());
        when(conversations.projectAuthorizationMode(any(), eq(agent), eq(conversation), eq(run)))
                .thenReturn(OperationAuthorizationMode.ASK_ON_EFFECT);

        var result = controller.projectAuthorizationMode(agent, conversation, run, authentication,
                new MockHttpServletRequest());

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().mode()).isEqualTo(OperationAuthorizationMode.ASK_ON_EFFECT);
        verify(conversations).projectAuthorizationMode(any(), eq(agent), eq(conversation), eq(run));
    }
}
