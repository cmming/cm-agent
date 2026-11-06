package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.LocalProject;
import com.cmagent.core.repository.*;
import com.cmagent.server.audit.AuditAppender;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 无文件/进程依赖的归档合同；事务夹具只验证边界，真实CAS及回滚另由双库RecoveryTest验证。 */
class LocalProjectArchiveServiceTest {
    private final PrincipalRef owner = new PrincipalRef(UUID.randomUUID(), "Owner", "测试主体", Set.of("project:manage"));
    private final UUID id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-30T00:00:00Z");
    private final LocalProjectAccessService access = mock(LocalProjectAccessService.class);
    private final LocalProjectRepository projects = mock(LocalProjectRepository.class);
    private final LocalProjectBindingRepository bindings = mock(LocalProjectBindingRepository.class);
    private final LocalProjectOperationRepository operations = mock(LocalProjectOperationRepository.class);
    private final AuditAppender audit = mock(AuditAppender.class);
    private final AtomicReference<LocalProject> row = new AtomicReference<>();
    private boolean rolledBack;
    private LocalProjectArchiveService service;

    @BeforeEach void setup() {
        row.set(new LocalProject(id, owner.tenantId(), "Owner", "归档测试", "node", UUID.randomUUID(), "a".repeat(64), LocalProject.State.ACTIVE, 1, now.minusSeconds(10), now.minusSeconds(10)));
        when(access.project(owner, id, "project:manage")).thenAnswer(call -> row.get());
        when(projects.lock(owner.tenantId(), "Owner", id)).thenAnswer(call -> Optional.of(row.get()));
        when(projects.archive(eq(owner.tenantId()), eq("Owner"), eq(id), anyLong(), any())).thenAnswer(call -> {
            if (row.get().version() != (long) call.getArgument(3)) return false;
            row.set(row.get().archive(call.getArgument(4))); return true;
        });
        when(access.reject(any(), any(), anyString(), anyInt(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new LocalProjectAccessException(call.getArgument(2), "archive-error", call.getArgument(3), call.getArgument(4));
        });
        when(access.infrastructure(any(), any(), anyString(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new LocalProjectAccessException("PROJECT_UNAVAILABLE", "archive-error", 503, "归档服务暂不可用");
        });
        var transaction = new TransactionTemplate(new AbstractPlatformTransactionManager() {
            private LocalProject before;
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object state, TransactionDefinition definition) { before = row.get(); }
            @Override protected void doCommit(DefaultTransactionStatus status) {}
            @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; row.set(before); }
        });
        service = new LocalProjectArchiveService(access, projects, bindings, operations, audit, transaction, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void 无在途归档只改登记且已归档幂等保留根和历史() {
        var original = row.get(); var archived = service.archive(owner, id, 1);
        assertThat(archived.state()).isEqualTo("ARCHIVED"); assertThat(archived.version()).isEqualTo(2);
        assertThat(row.get().rootBindingId()).isEqualTo(original.rootBindingId()); assertThat(row.get().rootIdentity()).isEqualTo(original.rootIdentity());
        assertThat(service.archive(owner, id, 1)).isEqualTo(archived);
        verify(projects, times(1)).archive(any(), anyString(), any(), anyLong(), any());
        verify(audit, times(1)).appendControlled(eq(owner.tenantId()), eq("Owner"), eq("PROJECT_ARCHIVE"), eq("LOCAL_PROJECT"), eq(id.toString()), eq("ARCHIVED"), anyString());
        verify(access, never()).scope(any(), any(), anyString());
    }

    @Test void 活动运行或未知操作拒绝并保留原上下文且拒绝审计不在回滚事务中() {
        when(bindings.hasActiveRuns(owner.tenantId(), "Owner", id)).thenReturn(true);
        rejected("PROJECT_ACTIVE_RUNS", 409, () -> service.archive(owner, id, 1));
        when(bindings.hasActiveRuns(owner.tenantId(), "Owner", id)).thenReturn(false);
        when(operations.hasUncertainOperation(owner.tenantId(), "Owner", id, null)).thenReturn(true);
        rejected("PROJECT_UNCERTAIN_OPERATIONS", 409, () -> service.archive(owner, id, 1));
        assertThat(row.get().state()).isEqualTo(LocalProject.State.ACTIVE);
        verify(projects, never()).archive(any(), anyString(), any(), anyLong(), any()); verifyNoInteractions(audit);
    }

    @Test void 版本失配及CAS竞争均409且不自动重试() {
        rejected("PROJECT_VERSION_CONFLICT", 409, () -> service.archive(owner, id, 2));
        doReturn(false).when(projects).archive(any(), anyString(), any(), anyLong(), any());
        rejected("PROJECT_VERSION_CONFLICT", 409, () -> service.archive(owner, id, 1));
        verify(projects, times(1)).archive(any(), anyString(), any(), anyLong(), any()); assertThat(row.get().state()).isEqualTo(LocalProject.State.ACTIVE);
    }

    @Test void 权限拒绝不访问项目锁而损坏owner不能旁路归档() {
        doThrow(new LocalProjectAccessException("PROJECT_FORBIDDEN", "archive-error", 403, "无归档权限")).when(access).project(owner, id, "project:manage");
        rejected("PROJECT_FORBIDDEN", 403, () -> service.archive(owner, id, 1)); verifyNoInteractions(projects);
        doReturn(row.get()).when(access).project(owner, id, "project:manage");
        var original = row.get(); row.set(new LocalProject(id, owner.tenantId(), "owner", original.name(), original.nodeId(), original.rootBindingId(), original.rootIdentity(), original.state(), 1, original.createdAt(), original.updatedAt()));
        rejected("PROJECT_NOT_FOUND", 404, () -> service.archive(owner, id, 1));
        verify(projects, never()).archive(any(), anyString(), any(), anyLong(), any());
    }

    @Test void 严格成功审计失败回滚归档并沿用安全错误编号() {
        doThrow(new IllegalStateException("SELECT private C:\\private\\root")).when(audit).appendControlled(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        rejected("PROJECT_UNAVAILABLE", 503, () -> service.archive(owner, id, 1));
        assertThat(rolledBack).isTrue(); assertThat(row.get().state()).isEqualTo(LocalProject.State.ACTIVE); assertThat(row.get().version()).isEqualTo(1);
        verify(access).infrastructure(eq(owner), eq(id), eq("PROJECT_ARCHIVE"), any());
    }

    private static void rejected(String code, int status, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(LocalProjectAccessException.class, error -> {
            assertThat(error.status()).isEqualTo(status); assertThat(error.errorCode()).isEqualTo(code); assertThat(error.errorId()).isEqualTo("archive-error");
            assertThat(error.getMessage()).doesNotContain("SELECT", "private", "C:\\");
        });
    }
}
