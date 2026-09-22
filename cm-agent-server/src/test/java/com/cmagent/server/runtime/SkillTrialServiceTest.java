package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.SkillLoadRecord;
import com.cmagent.core.domain.SkillLoadStatus;
import com.cmagent.core.domain.SkillTrial;
import com.cmagent.core.domain.SkillTrialStatus;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillLoadRecordRepository;
import com.cmagent.core.repository.SkillTrialRepository;
import com.cmagent.core.repository.SkillVersionRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.service.SkillPreflightService;
import com.cmagent.server.service.SkillUnitOfWork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillTrialServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");
    private final UUID tenant = UUID.randomUUID();
    private final UUID skill = UUID.randomUUID();
    private final UUID version = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();
    private final PrincipalRef principal = new PrincipalRef(tenant, "tester", "测试者", Set.of("skill:write", "agent:run"));

    @Mock private SkillDefinitionRepository definitions;
    @Mock private SkillVersionRepository versions;
    @Mock private SkillTrialRepository trials;
    @Mock private SkillLoadRecordRepository loads;
    @Mock private SkillPreflightService preflights;
    @Mock private RunPersistenceService runs;
    @Mock private RunExecutionService execution;
    @Mock private ToolApprovalService approvals;
    @Mock private AuditAppender audit;

    private SkillTrialService service;

    @BeforeEach
    void setUp() {
        SkillUnitOfWork unit = new SkillUnitOfWork() {
            @Override
            public <T> T execute(java.util.function.Supplier<T> operation) {
                return operation.get();
            }
        };
        service = new SkillTrialService(definitions, versions, trials, loads, preflights, runs, execution, approvals,
                unit, audit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 只有目标技能指定版本的成功读取才能成为发布依据() {
        SkillTrial trial = runningTrial();
        SkillLoadRecord targetLoad = new SkillLoadRecord(UUID.randomUUID(), tenant, run, "call-1", 1,
                skill, version, "SKILL.md", SkillLoadStatus.SUCCEEDED, 20, 1,
                null, null, NOW);
        when(trials.find(tenant, run)).thenReturn(Optional.of(trial));
        when(loads.listForBudget(tenant, run)).thenReturn(List.of(targetLoad));
        when(trials.update(any(), eq(SkillTrialStatus.RUNNING))).thenReturn(true);

        SkillTrial result = service.finalizeResult(principal, run,
                new AgentRunResult(run, RunStatus.SUCCEEDED, "", List.of(), NOW, NOW, ""));

        assertThat(result.status()).isEqualTo(SkillTrialStatus.PASSED);
        assertThat(result.qualifiesRelease()).isTrue();
        ArgumentCaptor<SkillTrial> next = ArgumentCaptor.forClass(SkillTrial.class);
        verify(trials).update(next.capture(), eq(SkillTrialStatus.RUNNING));
        assertThat(next.getValue().versionId()).isEqualTo(version);
    }

    @Test
    void 成功但只读取其他技能时不能发布且等待审批保持独立状态() {
        SkillTrial trial = runningTrial();
        SkillLoadRecord otherLoad = new SkillLoadRecord(UUID.randomUUID(), tenant, run, "call-2", 1,
                UUID.randomUUID(), UUID.randomUUID(), "SKILL.md", SkillLoadStatus.SUCCEEDED, 20, 1,
                null, null, NOW);
        when(trials.find(tenant, run)).thenReturn(Optional.of(trial));
        when(loads.listForBudget(tenant, run)).thenReturn(List.of(otherLoad));
        when(trials.update(any(), eq(SkillTrialStatus.RUNNING))).thenReturn(true);

        SkillTrial notTriggered = service.finalizeResult(principal, run,
                new AgentRunResult(run, RunStatus.SUCCEEDED, "", List.of(), NOW, NOW, ""));
        SkillTrial waiting = service.finalizeResult(principal, run,
                new AgentRunResult(run, RunStatus.WAITING_APPROVAL, "", List.of(), NOW, null, ""));

        assertThat(notTriggered.status()).isEqualTo(SkillTrialStatus.NOT_TRIGGERED);
        assertThat(notTriggered.qualifiesRelease()).isFalse();
        assertThat(waiting.status()).isEqualTo(SkillTrialStatus.WAITING_APPROVAL);
    }

    private SkillTrial runningTrial() {
        return new SkillTrial(run, tenant, skill, version, agent, 3, SkillTrialStatus.RUNNING,
                false, principal.principalId(), NOW.minusSeconds(1), NOW.minusSeconds(1));
    }
}
