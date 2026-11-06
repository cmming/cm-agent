package com.cmagent.server.runtime;

import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.server.audit.AuditAppender;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 纯元数据恢复合同；事务管理器替身验证传播及回滚调用，真实JDBC回滚在双库RecoveryTest核验。 */
class LocalProjectRecoveryServiceTest {
    private final LocalProjectBindingRepository bindings = mock(LocalProjectBindingRepository.class);
    private final LocalProjectOperationRepository operations = mock(LocalProjectOperationRepository.class);
    private final RunRepository runs = mock(RunRepository.class);
    private final AuditAppender audit = mock(AuditAppender.class);
    private final LocalProjectStateLock state = mock(LocalProjectStateLock.class);
    private final Map<UUID,RunRecord> runRows = new HashMap<>();
    private final Map<UUID,LocalProjectOperation> operationRows = new HashMap<>();
    private final List<LocalProjectBinding> bindingRows = new ArrayList<>();
    private final Instant now = Instant.parse("2026-10-30T00:00:00Z");
    private LocalProjectRecoveryService service;
    private boolean rollback;

    @BeforeEach void setup() {
        when(bindings.findInterruptedRuns(eq("node"), anyInt())).thenAnswer(call -> bindingRows.stream()
                .filter(value -> value.nodeId().equals("node"))
                .filter(value -> Set.of(RunStatus.RUNNING, RunStatus.CANCELLING).contains(runRows.get(value.runId()).status())).toList());
        when(runs.findByTenantAndAgentAndId(any(), any(), any())).thenAnswer(call -> Optional.ofNullable(runRows.get(call.getArgument(2))));
        when(runs.workspaceProtocol(any(), any(), any())).thenReturn(4);
        when(operations.findRunningForRun(any(), anyInt())).thenAnswer(call -> {
            LocalProjectBinding binding = call.getArgument(0);
            return operationRows.values().stream().filter(value -> value.runId().equals(binding.runId()) && value.state() == LocalProjectOperation.State.RUNNING).toList();
        });
        when(operations.advance(any(), anyString(), any(), anyLong(), eq(LocalProjectOperation.State.UNKNOWN), any())).thenAnswer(call -> {
            LocalProjectOperation old = operationRows.get(call.getArgument(2));
            var next = old.advance(LocalProjectOperation.State.UNKNOWN, call.getArgument(5)); operationRows.put(next.id(), next); return Optional.of(next);
        });
        when(runs.failInterrupted(any(), any())).thenAnswer(call -> {
            RunRecord old = call.getArgument(0); Instant finished = call.getArgument(1);
            if (runRows.get(old.id()).status() != old.status()) return false;
            runRows.put(old.id(), new RunRecord(old.id(), old.tenantId(), old.agentId(), old.principalId(), old.kind(), RunStatus.FAILED,
                    old.input(), "", RunRepository.INTERRUPTED_FAILURE_MESSAGE, old.startedAt(), finished)); return true;
        });
        var transaction = new TransactionTemplate(new PlatformTransactionManager() {
            private Map<UUID,RunRecord> savedRuns;
            private Map<UUID,LocalProjectOperation> savedOperations;
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) { savedRuns = Map.copyOf(runRows); savedOperations = Map.copyOf(operationRows); return new SimpleTransactionStatus(); }
            @Override public void commit(TransactionStatus status) {}
            @Override public void rollback(TransactionStatus status) { rollback = true; runRows.clear(); runRows.putAll(savedRuns); operationRows.clear(); operationRows.putAll(savedOperations); }
        });
        service = new LocalProjectRecoveryService("node", state, Clock.fixed(now, ZoneOffset.UTC), bindings, operations, runs, audit, transaction);
    }

    @Test void 只收口当前节点活动运行未知操作保留等待审批及其他节点不变() {
        var running = add(RunStatus.RUNNING, "node"); var cancelling = add(RunStatus.CANCELLING, "node");
        var waiting = add(RunStatus.WAITING_APPROVAL, "node"); var foreign = add(RunStatus.RUNNING, "other-node");
        var file = operation(running, LocalProjectOperation.Kind.FILE_CHANGE, LocalProjectOperation.State.RUNNING);
        var command = operation(cancelling, LocalProjectOperation.Kind.COMMAND, LocalProjectOperation.State.RUNNING);
        var unknown = operation(running, LocalProjectOperation.Kind.COMMAND, LocalProjectOperation.State.UNKNOWN);
        var prepared = operation(waiting, LocalProjectOperation.Kind.COMMAND, LocalProjectOperation.State.PREPARED);
        assertThat(service.recover()).isEqualTo(2); assertThat(service.recover()).isZero();
        assertThat(runRows.get(running.runId()).status()).isEqualTo(RunStatus.FAILED);
        assertThat(runRows.get(cancelling.runId()).status()).isEqualTo(RunStatus.FAILED);
        assertThat(runRows.get(cancelling.runId()).errorMessage()).isEqualTo(RunRepository.INTERRUPTED_FAILURE_MESSAGE);
        assertThat(runRows.get(waiting.runId()).status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(runRows.get(foreign.runId()).status()).isEqualTo(RunStatus.RUNNING);
        assertThat(operationRows.get(file.id()).state()).isEqualTo(LocalProjectOperation.State.UNKNOWN);
        assertThat(operationRows.get(command.id()).state()).isEqualTo(LocalProjectOperation.State.UNKNOWN);
        assertThat(operationRows.get(unknown.id())).isEqualTo(unknown); assertThat(operationRows.get(prepared.id())).isEqualTo(prepared);
        verify(audit, times(2)).appendControlled(any(), anyString(), eq("PROJECT_COLD_START_RECOVERY"), eq("RUN"), anyString(), eq("FAILED"), anyString());
    }

    @Test void 严格审计失败回滚运行与操作并只向启动边界提供安全同号原因() {
        var binding = add(RunStatus.RUNNING, "node"); var operation = operation(binding, LocalProjectOperation.Kind.COMMAND, LocalProjectOperation.State.RUNNING);
        doThrow(new IllegalStateException("SELECT private_body C:\\private\\root")).when(audit)
                .appendControlled(any(), anyString(), eq("PROJECT_COLD_START_RECOVERY"), anyString(), anyString(), anyString(), anyString());
        assertThatThrownBy(service::recover).isInstanceOfSatisfying(LocalProjectRecoveryService.RecoveryFailure.class, failure -> {
            assertThat(failure.errorId()).isNotBlank(); assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).contains(failure.errorId(), "PROJECT_RECOVERY_FAILED").doesNotContain("SELECT", "private_body", "private\\root");
        });
        assertThat(rollback).isTrue(); assertThat(runRows.get(binding.runId()).status()).isEqualTo(RunStatus.RUNNING);
        assertThat(operationRows.get(operation.id())).isEqualTo(operation);
    }

    @Test void 运行CAS失败回滚UNKNOWN且不能删除等待审批检查点() {
        var binding = add(RunStatus.CANCELLING, "node"); var operation = operation(binding, LocalProjectOperation.Kind.FILE_CHANGE, LocalProjectOperation.State.RUNNING);
        doReturn(false).when(runs).failInterrupted(any(), any());
        assertThatThrownBy(service::recover).isInstanceOf(LocalProjectRecoveryService.RecoveryFailure.class);
        assertThat(rollback).isTrue(); assertThat(operationRows.get(operation.id())).isEqualTo(operation);
        assertThat(runRows.get(binding.runId()).status()).isEqualTo(RunStatus.CANCELLING);
    }

    @Test void 未持有实例锁或来源协议降级均拒绝恢复() {
        var binding = add(RunStatus.RUNNING, "node");
        doThrow(new IllegalStateException("原生锁已失效")).when(state).requireHeld();
        assertThatThrownBy(service::recover).isInstanceOf(LocalProjectRecoveryService.RecoveryFailure.class);
        verifyNoInteractions(operations); verifyNoInteractions(audit);
        doNothing().when(state).requireHeld(); when(runs.workspaceProtocol(any(), any(), any())).thenReturn(0);
        assertThatThrownBy(service::recover).isInstanceOf(LocalProjectRecoveryService.RecoveryFailure.class);
        assertThat(runRows.get(binding.runId()).status()).isEqualTo(RunStatus.RUNNING);
        verify(runs, never()).failInterrupted(any(), any());
    }

    private LocalProjectBinding add(RunStatus status, String node) {
        UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), id = UUID.randomUUID();
        var binding = new LocalProjectBinding(tenant, "Owner", UUID.randomUUID(), agent, UUID.randomUUID(), id, UUID.randomUUID(), "a".repeat(64), node, 1, now.minusSeconds(60));
        bindingRows.add(binding); runRows.put(id, new RunRecord(id, tenant, agent, "Owner", RunKind.NORMAL, status, "受保护原输入", "", "", now.minusSeconds(60), null)); return binding;
    }
    private LocalProjectOperation operation(LocalProjectBinding binding, LocalProjectOperation.Kind kind, LocalProjectOperation.State status) {
        var value = new LocalProjectOperation(UUID.randomUUID(), binding.tenantId(), binding.ownerId(), binding.projectId(), binding.agentId(), binding.conversationId(),
                binding.runId(), kind, "b".repeat(64), "v1:" + Base64.getEncoder().encodeToString(new byte[12]) + ":" + Base64.getEncoder().encodeToString(new byte[32]), status, 4, now.minusSeconds(30), now.minusSeconds(30));
        operationRows.put(value.id(), value); return value;
    }
}
