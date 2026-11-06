package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅验证元数据治理；合成事务管理器验证事务边界，真实数据库回滚及CAS由双库集成测试负责。 */
class LocalProjectOperationMaintenanceTest {
    /** 场景固定可信租户。 */ private final UUID tenant = UUID.randomUUID();
    /** 场景固定项目，可归档且无需活根。 */ private final UUID project = UUID.randomUUID();
    /** 原始运行及其不可变来源。 */ private final UUID runId = UUID.randomUUID();
    /** 元数据归属的 Agent。 */ private final UUID agent = UUID.randomUUID();
    /** 元数据归属的会话。 */ private final UUID conversation = UUID.randomUUID();
    /** 墓碑原操作标识。 */ private final UUID operationId = UUID.randomUUID();
    /** 晚于七日隐私擦除期限的固定可信时间。 */ private final Instant now = Instant.parse("2026-10-29T10:00:00Z");
    /** 认证上下文；权限核验委托独立 access 边界，不从请求布尔值推断。 */
    private final PrincipalRef principal = new PrincipalRef(tenant, "Owner", "合成主体", Set.of("project:read", "project:manage", "agent:run", "project:execute", "project:write"));
    /** mock只返回受信元数据，不持有文件/进程能力。 */ private LocalProjectOperationRepository operations;
    /** 长期固定来源查询。 */ private LocalProjectBindingRepository bindings;
    /** 原始Run元数据读取。 */ private RunRepository runs;
    /** 统一权限与脱敏错误入口。 */ private LocalProjectAccessService access;
    /** 项目来源核验，不打开根。 */ private LocalProjectResourceAccess resources;
    /** 严格审计参与与CAS同一短事务。 */ private AuditAppender audit;
    /** 合成事务仅记录提交/回滚，不伪装为真实数据库。 */ private TrackingTransactions manager;
    /** 本次维护服务。 */ private LocalProjectOperationMaintenance service;
    /** 原持久来源，创建时间早于隐私期限。 */ private LocalProjectBinding binding;
    /** 原Run，输入不应进入View或异常。 */ private RunRecord run;

    @BeforeEach void setup() {
        operations = mock(LocalProjectOperationRepository.class);
        bindings = mock(LocalProjectBindingRepository.class);
        runs = mock(RunRepository.class);
        access = mock(LocalProjectAccessService.class);
        resources = mock(LocalProjectResourceAccess.class);
        audit = mock(AuditAppender.class);
        manager = new TrackingTransactions();
        binding = new LocalProjectBinding(tenant, "Owner", project, agent, conversation, runId, UUID.randomUUID(),
                "a".repeat(64), "offline-node", 1, now.minusSeconds(864000));
        run = RunRecord.create(runId, tenant, agent, "Owner", "PRIVATE_ORIGINAL_INPUT", binding.createdAt());
        when(bindings.run(tenant, "Owner", runId)).thenReturn(Optional.of(binding));
        when(runs.findByTenantAndAgentAndId(tenant, agent, runId)).thenReturn(Optional.of(run));
        when(resources.run(principal, run)).thenReturn(binding);
        when(access.reject(any(), any(), anyString(), anyInt(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new LocalProjectAccessException(call.getArgument(2), UUID.randomUUID().toString(), call.getArgument(3), call.getArgument(4));
        });
        when(access.infrastructure(any(), any(), anyString(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new LocalProjectAccessException("PROJECT_UNAVAILABLE", UUID.randomUUID().toString(), 503, "本地项目服务暂不可用");
        });
        service = new LocalProjectOperationMaintenance(operations, bindings, runs, access, resources, audit,
                new TransactionTemplate(manager), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void 已擦除墓碑只由原owner精确权限终结且不触碰根或正文() throws Exception {
        var unknown = operation(LocalProjectOperation.Kind.COMMAND, LocalProjectOperation.State.UNKNOWN, 5);
        var cancelled = unknown.advance(LocalProjectOperation.State.CANCELLED, now);
        when(operations.operation(tenant, "Owner", operationId)).thenReturn(Optional.of(unknown), Optional.of(unknown), Optional.of(cancelled));
        when(operations.advance(tenant, "Owner", operationId, 5, LocalProjectOperation.State.CANCELLED, now)).thenReturn(Optional.of(cancelled));
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        }).when(audit).appendControlled(any(), any(), any(), any(), any(), any(), any());
        var result = service.resolveUnknown(principal, project, runId, operationId, 5, true);
        assertThat(result.operationId()).isEqualTo(operationId);
        assertThat(result.state()).isEqualTo(LocalProjectOperation.State.CANCELLED);
        assertThat(result.version()).isEqualTo(6);
        assertThat(result.digest()).isEqualTo(unknown.digest());
        assertThat(result.notice()).contains("不表示已撤销");
        String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(serialized).doesNotContain("encryptedPayload", unknown.encryptedPayload(), "PRIVATE_ORIGINAL_INPUT");
        verify(access).project(principal, project, "project:manage");
        verify(access).requireExecutionNode(principal, project);
        verify(access).authorize(principal, "agent:run", runId);
        verify(access).authorize(principal, "project:execute", project);
        verify(access, never()).scope(any(), any(), any());
        verify(access, never()).run(any(), any(), any());
        verify(audit).appendControlled(eq(tenant), eq("Owner"), eq("LOCAL_PROJECT_OPERATION_RESOLVE_UNKNOWN"),
                eq("PROJECT_OPERATION"), eq(operationId.toString()), eq("CANCELLED"), contains("previousState=UNKNOWN"));
        assertThat(manager.committed.get()).isTrue();
        assertThatThrownBy(() -> service.resolveUnknown(principal, project, runId, operationId, 5, true))
                .isInstanceOfSatisfying(LocalProjectAccessException.class, failure -> assertThat(failure.errorCode()).isEqualTo("PROJECT_OPERATION_CONFLICT"));
        verify(operations, times(1)).advance(any(), any(), any(), anyLong(), any(), any());
    }

    @Test void 文件意图要求write且请求确认不等于任何批准() {
        var unknown = operation(LocalProjectOperation.Kind.FILE_CHANGE, LocalProjectOperation.State.UNKNOWN, 5);
        when(operations.operation(tenant, "Owner", operationId)).thenReturn(Optional.of(unknown));
        assertRejected("PROJECT_OPERATION_ACKNOWLEDGEMENT_REQUIRED", 400, () -> service.resolveUnknown(principal, project, runId, operationId, 5, false));
        verify(access).authorize(principal, "project:write", project);
        verify(operations, never()).advance(any(), any(), any(), anyLong(), any(), any());
        verifyNoInteractions(audit);
        assertThat(manager.committed.get()).isFalse();
    }

    @Test void 非原执行节点不能人工收口或解除未知状态阻断() {
        var failure = new LocalProjectAccessException("PROJECT_NODE_UNAVAILABLE", UUID.randomUUID().toString(), 409, "项目所属执行节点不可用");
        when(access.requireExecutionNode(principal, project)).thenThrow(failure);
        assertThatThrownBy(() -> service.resolveUnknown(principal, project, runId, operationId, 5, true)).isSameAs(failure);
        verify(operations, never()).advance(any(), any(), any(), anyLong(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test void 错版本或非UNKNOWN不能终结也不能复活原意图() {
        for (var state : LocalProjectOperation.State.values()) {
            if (state == LocalProjectOperation.State.UNKNOWN) continue;
            when(operations.operation(tenant, "Owner", operationId)).thenReturn(Optional.of(operation(LocalProjectOperation.Kind.COMMAND, state, 5)));
            assertRejected("PROJECT_OPERATION_CONFLICT", 409, () -> service.resolveUnknown(principal, project, runId, operationId, 5, true));
        }
        when(operations.operation(tenant, "Owner", operationId)).thenReturn(Optional.of(operation(LocalProjectOperation.Kind.COMMAND, LocalProjectOperation.State.UNKNOWN, 6)));
        assertRejected("PROJECT_OPERATION_CONFLICT", 409, () -> service.resolveUnknown(principal, project, runId, operationId, 5, true));
        verify(operations, never()).advance(any(), any(), any(), anyLong(), any(), any());
    }

    @Test void 错owner大小写尾空格租户项目与Run均不泄露存在性() {
        for (var other : List.of(new PrincipalRef(tenant, "owner", "合成", Set.of()), new PrincipalRef(tenant, "Owner ", "合成", Set.of()),
                new PrincipalRef(UUID.randomUUID(), "Owner", "合成", Set.of()))) {
            assertRejected("PROJECT_OPERATION_NOT_FOUND", 404, () -> service.resolveUnknown(other, project, runId, operationId, 5, true));
        }
        assertRejected("PROJECT_OPERATION_NOT_FOUND", 404, () -> service.resolveUnknown(principal, UUID.randomUUID(), runId, operationId, 5, true));
        assertRejected("PROJECT_OPERATION_NOT_FOUND", 404, () -> service.resolveUnknown(principal, project, UUID.randomUUID(), operationId, 5, true));
        verify(operations, never()).advance(any(), any(), any(), anyLong(), any(), any());
    }

    @Test void 事务内CAS失去竞争后回滚并在事务外审计拒绝() {
        var unknown = operation(LocalProjectOperation.Kind.COMMAND, LocalProjectOperation.State.UNKNOWN, 5);
        when(operations.operation(tenant, "Owner", operationId)).thenReturn(Optional.of(unknown));
        when(operations.advance(tenant, "Owner", operationId, 5, LocalProjectOperation.State.CANCELLED, now)).thenReturn(Optional.empty());
        assertRejected("PROJECT_OPERATION_CONFLICT", 409, () -> service.resolveUnknown(principal, project, runId, operationId, 5, true));
        assertThat(manager.rolledBack.get()).isTrue();
        verifyNoInteractions(audit);
    }

    @Test void 严格审计故障触发事务回滚并只交付安全同号基础设施错误() {
        var unknown = operation(LocalProjectOperation.Kind.COMMAND, LocalProjectOperation.State.UNKNOWN, 5);
        when(operations.operation(tenant, "Owner", operationId)).thenReturn(Optional.of(unknown));
        when(operations.advance(tenant, "Owner", operationId, 5, LocalProjectOperation.State.CANCELLED, now))
                .thenReturn(Optional.of(unknown.advance(LocalProjectOperation.State.CANCELLED, now)));
        doThrow(new AuditPersistenceException("PRIVATE_SQL_OR_SECRET", new IllegalStateException("PRIVATE_INTERNAL_PATH")))
                .when(audit).appendControlled(any(), any(), any(), any(), any(), any(), any());
        assertRejected("PROJECT_UNAVAILABLE", 503, () -> service.resolveUnknown(principal, project, runId, operationId, 5, true));
        assertThat(manager.rolledBack.get()).isTrue();
        assertThat(manager.committed.get()).isFalse();
        verify(access).infrastructure(eq(principal), eq(operationId), eq("PROJECT_OPERATION_RESOLVE_UNKNOWN"), any(AuditPersistenceException.class));
    }

    @Test void 列表只返回安全DTO且上限无效不访问仓储() throws Exception {
        var unknown = operation(LocalProjectOperation.Kind.COMMAND, LocalProjectOperation.State.UNKNOWN, 5);
        when(operations.listRun(tenant, "Owner", project, runId, 10)).thenReturn(List.of(unknown));
        var result = service.listRun(principal, project, runId, 10);
        assertThat(result).singleElement().satisfies(view -> assertThat(view.operationId()).isEqualTo(operationId));
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(result)).doesNotContain("encryptedPayload", unknown.encryptedPayload());
        assertRejected("PROJECT_OPERATION_LIMIT_INVALID", 400, () -> service.listRun(principal, project, runId, 101));
        verify(operations, never()).listRun(any(), any(), any(), any(), eq(101));
    }

    private LocalProjectOperation operation(LocalProjectOperation.Kind kind, LocalProjectOperation.State state, long version) {
        // 用随机内存密钥生成真实AEAD墓碑，既满足领域信封约束，也不依赖任何部署凭据。
        String tombstone = new ModelCredentialCipher(new javax.crypto.spec.SecretKeySpec(java.security.SecureRandom.getSeed(32),"AES"))
                .encrypt("expired-operation-fixture");
        return new LocalProjectOperation(operationId, tenant, "Owner", project, agent, conversation, runId, kind, "b".repeat(64), tombstone,
                state, version, binding.createdAt(), binding.createdAt());
    }

    private static void assertRejected(String code, int status, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(LocalProjectAccessException.class, failure -> {
            assertThat(failure.errorCode()).isEqualTo(code);
            assertThat(failure.status()).isEqualTo(status);
            assertThat(failure.errorId()).isNotBlank();
            assertThat(failure.getMessage()).doesNotContain("PRIVATE_", "encryptedPayload");
        });
    }

    /** 无外部资源的事务夹具只验证框架提交/回滚边界，数据库原子性必须另以双库验证。 */
    private static final class TrackingTransactions extends AbstractPlatformTransactionManager {
        /** 记录是否走到正式提交。 */ private final AtomicBoolean committed = new AtomicBoolean();
        /** 记录受控拒绝或严格失败是否触发回滚。 */ private final AtomicBoolean rolledBack = new AtomicBoolean();
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) { committed.set(true); }
        @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack.set(true); }
    }
}
