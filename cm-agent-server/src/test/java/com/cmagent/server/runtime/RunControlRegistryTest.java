package com.cmagent.server.runtime;

import com.cmagent.core.domain.RunRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunControlRegistryTest {
    @Test
    void 前片段迟到释放不能删除审批后新片段句柄() {
        var registry = new RunControlRegistry();
        var run = RunRecord.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tester", "输入", Instant.now());
        var conversation = UUID.randomUUID();
        var previous = registry.register(run, conversation);
        registry.release(run.id());
        var resumed = registry.register(run, conversation);
        registry.release(run.id(), previous);
        assertThat(registry.find(run.id())).isSameAs(resumed);
        registry.release(run.id(), resumed);
        assertThat(registry.find(run.id())).isNull();
    }
}
