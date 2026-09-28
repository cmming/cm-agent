package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunKind;
import com.cmagent.core.domain.RunRecord;
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
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.service.SkillPreflightService;
import com.cmagent.server.service.SkillUnitOfWork;
import com.cmagent.core.runtime.SkillAccessException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                unit, audit, enabledProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
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

    @Test
    void 技能功能关闭时拒绝创建会产生真实副作用的试运行() {
        SkillUnitOfWork unit = new SkillUnitOfWork() {
            @Override
            public <T> T execute(java.util.function.Supplier<T> operation) {
                return operation.get();
            }
        };
        SkillTrialService disabled = new SkillTrialService(definitions, versions, trials, loads, preflights, runs,
                execution, approvals, unit, audit, new SkillProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> disabled.start(principal, skill, version, agent, "测试"))
                .isInstanceOfSatisfying(SkillAccessException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ApiErrorCode.SKILL_FEATURE_DISABLED));
    }

    @Test
    void 结果预览通过可信租户和Agent读取TEST运行明细() {
        SkillTrial trial = runningTrial();
        RunRecord runRecord = new RunRecord(run, tenant, agent, principal.principalId(), RunKind.TEST,
                RunStatus.SUCCEEDED, "已脱敏输入", "已脱敏输出", "", NOW, NOW);
        RunPersistenceService.RunDetail detail = new RunPersistenceService.RunDetail(runRecord, List.of());
        when(trials.find(tenant, run)).thenReturn(Optional.of(trial));
        when(runs.findDetail(tenant, agent, run)).thenReturn(detail);

        assertThat(service.runDetail(principal, run)).isSameAs(detail);
        verify(runs).findDetail(tenant, agent, run);
    }

    @Test
    void 其他主体不能查询试运行运行结果() {
        SkillTrial otherOwnerTrial = new SkillTrial(run, tenant, skill, version, agent, 3,
                SkillTrialStatus.PASSED, true, "other-user", NOW.minusSeconds(1), NOW);
        when(trials.find(tenant, run)).thenReturn(Optional.of(otherOwnerTrial));

        assertThatThrownBy(() -> service.runDetail(principal, run))
                .isInstanceOf(SkillAccessException.class)
                .satisfies(failure -> assertThat(((SkillAccessException) failure).code())
                        .isEqualTo(ApiErrorCode.SKILL_NOT_FOUND));
        org.mockito.Mockito.verifyNoInteractions(runs);
    }

    private static SkillProperties enabledProperties() {
        SkillProperties result = new SkillProperties();
        result.setEnabled(true);
        return result;
    }

    private SkillTrial runningTrial() {
        return new SkillTrial(run, tenant, skill, version, agent, 3, SkillTrialStatus.RUNNING,
                false, principal.principalId(), NOW.minusSeconds(1), NOW.minusSeconds(1));
    }
}
