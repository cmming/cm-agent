package com.cmagent.server.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.ClientProjectRunOwnership;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.repository.ClientProjectBindingRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.persistence.JdbcClientProjectRunOwnershipRepository;
import com.cmagent.persistence.JdbcClientProjectRunRecoveryRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.ClientProjectProperties;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class ClientProjectRunRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    @Test
    void 过期代次先接管再等设备确认后才收口且保留未知任务() {
        var ownership = mock(JdbcClientProjectRunOwnershipRepository.class);
        var recovery = mock(JdbcClientProjectRunRecoveryRepository.class);
        var bindings = mock(ClientProjectBindingRepository.class);
        var runs = mock(RunRepository.class);
        var transaction = mock(TransactionTemplate.class);
        var audit = mock(AuditAppender.class);
        var diagnostics = mock(ErrorDiagnosticLogger.class);
        var identity = new ClientProjectRuntimeIdentity(UUID.randomUUID());
        var tenant = UUID.randomUUID();
        var owner = "owner";
        var runId = UUID.randomUUID();
        var old = new ClientProjectRunOwnership(tenant, runId, owner, UUID.randomUUID(), 1,
                ClientProjectRunOwnership.Phase.EXECUTING, NOW.minusSeconds(1));
        var recovering = new ClientProjectRunOwnership(tenant, runId, owner, identity.instanceId(), 2,
                ClientProjectRunOwnership.Phase.RECOVERING, NOW.plusSeconds(30));
        var expired = new JdbcClientProjectRunRecoveryRepository.Candidate(tenant, owner, runId,
                old.instanceId(), 1, old.phase());
        var acknowledged = new JdbcClientProjectRunRecoveryRepository.Candidate(tenant, owner, runId,
                identity.instanceId(), 2, recovering.phase());
        when(recovery.findExpired(any(Integer.class))).thenReturn(List.of(expired));
        when(recovery.findAcknowledgedRecovering(identity.instanceId(), 100)).thenReturn(List.of(acknowledged));
        when(ownership.takeOverForRecovery(tenant, owner, runId, 1, identity.instanceId(), Duration.ofSeconds(30)))
                .thenReturn(Optional.of(recovering));
        when(ownership.lockRecovery(tenant, owner, runId, identity.instanceId(), 2))
                .thenReturn(Optional.of(recovering));
        var binding = mock(ClientProjectBinding.class);
        when(binding.protocol()).thenReturn(ClientProjectBinding.PROTOCOL);
        var agentId = UUID.randomUUID();
        when(binding.agentId()).thenReturn(agentId);
        when(bindings.run(tenant, owner, runId)).thenReturn(Optional.of(binding));
        var run = mock(RunRecord.class);
        when(run.id()).thenReturn(runId);
        when(run.tenantId()).thenReturn(tenant);
        when(run.agentId()).thenReturn(agentId);
        when(run.principalId()).thenReturn(owner);
        when(run.status()).thenReturn(RunStatus.RUNNING);
        when(runs.findByTenantAndAgentAndId(tenant, agentId, runId)).thenReturn(Optional.of(run));
        when(runs.failClientProjectRecovered(run, NOW)).thenReturn(true);
        when(ownership.closeCurrent(tenant, owner, runId, identity.instanceId())).thenReturn(true);
        doAnswer(call -> ((org.springframework.transaction.support.TransactionCallback<?>) call.getArgument(0))
                .doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)))
                .when(transaction).execute(any());
        var properties = new ClientProjectProperties();
        properties.setOwnershipLease(Duration.ofSeconds(30));
        properties.setRecoveryScanInterval(Duration.ofSeconds(5));
        var service = new ClientProjectRunRecoveryService(ownership, recovery, bindings, runs, identity,
                transaction, audit, diagnostics, Clock.fixed(NOW, ZoneOffset.UTC), properties);

        service.maintain();

        verify(recovery).renewInstance(identity.instanceId(), Duration.ofSeconds(30));
        verify(ownership).takeOverForRecovery(tenant, owner, runId, 1, identity.instanceId(),
                Duration.ofSeconds(30));
        verify(runs).failClientProjectRecovered(run, NOW);
        verify(ownership).closeCurrent(tenant, owner, runId, identity.instanceId());
        verify(audit).appendControlled(tenant, "system:client-project-recovery",
                "CLIENT_PROJECT_RUN_RECOVERY_ACK", "CLIENT_PROJECT_RUN", runId.toString(),
                "FAILED", "epoch=2");
        verifyNoInteractions(diagnostics);
    }

    @Test
    void 前代实例失去归属后不能继续写Run终态() {
        var ownership = mock(JdbcClientProjectRunOwnershipRepository.class);
        var runs = mock(RunRepository.class);
        var identity = new ClientProjectRuntimeIdentity(UUID.randomUUID());
        var run = mock(RunRecord.class);
        UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), runId = UUID.randomUUID();
        when(run.tenantId()).thenReturn(tenant);
        when(run.agentId()).thenReturn(agent);
        when(run.id()).thenReturn(runId);
        when(run.principalId()).thenReturn("owner");
        when(runs.workspaceProtocol(tenant, agent, runId)).thenReturn(ClientProjectBinding.PROTOCOL);
        when(ownership.lockCurrentForInstance(tenant, "owner", runId, identity.instanceId()))
                .thenReturn(Optional.empty());
        var properties = new ClientProjectProperties();
        properties.setOwnershipLease(Duration.ofSeconds(30));
        properties.setRecoveryScanInterval(Duration.ofSeconds(5));
        var service = new ClientProjectRunRecoveryService(ownership,
                mock(JdbcClientProjectRunRecoveryRepository.class), mock(ClientProjectBindingRepository.class),
                runs, identity, mock(TransactionTemplate.class), mock(AuditAppender.class),
                mock(ErrorDiagnosticLogger.class), Clock.fixed(NOW, ZoneOffset.UTC), properties);

        org.springframework.transaction.support.TransactionSynchronizationManager
                .setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.requireCurrentOwnership(run))
                    .hasMessageContaining("CLIENT_PROJECT_EXECUTION_FENCE_LOST");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .setActualTransactionActive(false);
        }
    }
}
