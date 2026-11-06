package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.LocalProject;
import com.cmagent.core.repository.*;
import com.cmagent.core.security.*;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LocalProjectAccessServiceTest {
    private final PrincipalRef owner = new PrincipalRef(UUID.randomUUID(), "Owner", "测试", Set.of("project:read"));
    private final UUID projectId = UUID.randomUUID();
    private final LocalProjectRepository projects = mock(LocalProjectRepository.class);
    private final LocalProjectRootRepository roots = mock(LocalProjectRootRepository.class);
    private final LocalProjectBindingRepository bindings = mock(LocalProjectBindingRepository.class);
    private final PermissionEvaluator permissions = mock(PermissionEvaluator.class);
    private final AuditAppender audit = mock(AuditAppender.class);
    private final ErrorDiagnosticLogger diagnostics = mock(ErrorDiagnosticLogger.class);
    private LocalProjectAccessService access;

    @BeforeEach void setup() {
        when(permissions.check(any(), anyString())).thenReturn(AuthorizationDecision.allow());
        access = new LocalProjectAccessService(projects, roots, bindings, permissions, audit, diagnostics, null, null, "node");
    }

    @Test void owner仓储过滤使用可信租户和精确主体且权限拒绝先于查询() {
        when(projects.find(owner.tenantId(), "Owner", projectId)).thenReturn(Optional.empty());
        var missing = assertThrows(LocalProjectAccessException.class, () -> access.project(owner, projectId, "project:read"));
        assertEquals(404, missing.status()); assertEquals("PROJECT_NOT_FOUND", missing.errorCode());
        verify(projects).find(owner.tenantId(), "Owner", projectId);
        verify(audit).appendControlled(eq(owner.tenantId()), eq("Owner"), anyString(), eq("LOCAL_PROJECT"), eq(projectId.toString()), eq("DENIED"), contains(missing.errorId()));
        clearInvocations(projects);
        when(permissions.check(owner, "project:read")).thenReturn(AuthorizationDecision.deny("拒绝"));
        assertEquals(403, assertThrows(LocalProjectAccessException.class, () -> access.project(owner, projectId, "project:read")).status());
        verifyNoInteractions(projects);
    }

    @Test void 节点不同直接拒绝不解密或读取根且归档不可继续访问文件() {
        var now = Instant.now();
        var project = new LocalProject(projectId, owner.tenantId(), "Owner", "测试", "different-node", UUID.randomUUID(),
                "0".repeat(64), LocalProject.State.ACTIVE, 1, now, now);
        when(projects.find(owner.tenantId(), "Owner", projectId)).thenReturn(Optional.of(project));
        var rejected = assertThrows(LocalProjectAccessException.class, () -> access.scope(owner, projectId, "project:read"));
        assertEquals("PROJECT_NODE_UNAVAILABLE", rejected.errorCode()); assertEquals(409, rejected.status());
        verifyNoInteractions(roots);
        when(projects.find(owner.tenantId(), "Owner", projectId)).thenReturn(Optional.of(project.archive(now)));
        assertEquals("PROJECT_ARCHIVED", assertThrows(LocalProjectAccessException.class, () -> access.scope(owner, projectId, "project:read")).errorCode());
    }

    @Test void 基础设施失败日志和响应编号一致且原异常动态消息不进入日志() {
        when(projects.find(owner.tenantId(), "Owner", projectId)).thenThrow(new IllegalStateException("password=test-fixture-secret jdbc:mysql://internal.invalid/db F:\\private\\source"));
        var failure = assertThrows(LocalProjectAccessException.class, () -> access.project(owner, projectId, "project:read"));
        var context = ArgumentCaptor.forClass(ErrorDiagnosticLogger.DiagnosticContext.class);
        var logged = ArgumentCaptor.forClass(Throwable.class);
        verify(diagnostics).error(context.capture(), logged.capture(), eq("LOCAL_PROJECT"), eq(projectId.toString()));
        assertEquals(failure.errorId(), context.getValue().errorId()); assertEquals(503, failure.status());
        assertEquals("PROJECT_UNAVAILABLE", context.getValue().errorCode());
        assertNull(logged.getValue().getCause()); assertFalse(logged.getValue().toString().contains("test-fixture-secret"));
        assertFalse(failure.toString().contains("private")); assertTrue(logged.getValue().getStackTrace().length > 0);
    }

    @Test void 拒绝审计失败不能误报成普通403() {
        when(permissions.check(owner, "project:read")).thenReturn(AuthorizationDecision.deny("拒绝"));
        doThrow(new IllegalStateException("审计不可用")).when(audit).appendControlled(any(), any(), any(), any(), any(), any(), any());
        var failure = assertThrows(LocalProjectAccessException.class, () -> access.project(owner, projectId, "project:read"));
        assertEquals(503, failure.status()); assertEquals("PROJECT_UNAVAILABLE", failure.errorCode());
        verifyNoInteractions(projects);
    }
}
