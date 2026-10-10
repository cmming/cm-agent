package com.cmagent.core.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class RunRecordStopTest {
    @Test
    void 停止中不写完成时间且只允许取消或严格失败终态() {
        var run = run();
        var stopping = run.requestCancellation();
        assertThat(stopping.status().isActive()).isTrue();
        assertThat(stopping.finishedAt()).isNull();
        Instant end = run.startedAt().plusSeconds(1);
        assertThatThrownBy(() -> new RunRecord(run.id(), run.tenantId(), run.agentId(), run.principalId(),
                RunStatus.CANCELLING, "", "", "", run.startedAt(), end)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stopping.complete(RunStatus.SUCCEEDED, "迟到回答", "", end))
                .isInstanceOf(IllegalStateException.class);
        var stopped = stopping.complete(RunStatus.CANCELLED, "部分正文", "", end);
        assertThat(stopped.status().isActive()).isFalse();
        assertThatThrownBy(() -> stopped.complete(RunStatus.FAILED, "", "", end))
                .isInstanceOf(IllegalStateException.class);
        assertThat(stopping.complete(RunStatus.FAILED, "", "审计失败", end).status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void 批准恢复保留身份开始时间且停止后不允许恢复() {
        var run = run();
        var waiting = run.waitForApproval();
        var resumed = waiting.resumeAfterApproval();
        assertThat(resumed.id()).isEqualTo(run.id());
        assertThat(resumed.startedAt()).isEqualTo(run.startedAt());
        assertThat(resumed.status()).isEqualTo(RunStatus.RUNNING);
        assertThatThrownBy(() -> waiting.requestCancellation().resumeAfterApproval())
                .isInstanceOf(IllegalStateException.class);
    }

    private static RunRecord run() {
        return RunRecord.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tester", "输入", Instant.now());
    }
}
