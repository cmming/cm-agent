package com.cmagent.core.domain;

import com.cmagent.core.security.OperationAuthorizationMode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectRuntimeContextTest {
    @Test void 已配对设备项目使用同一受保护上下文但不伪造本机绑定() {
        var binding = new ClientProjectBinding(UUID.randomUUID(), "owner", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), 1, Instant.now());
        UUID tool = UUID.randomUUID();
        var context = new ProjectRuntimeContext(binding, Set.of(tool),
                OperationAuthorizationMode.ASK_ON_EFFECT);

        assertThat(context.binding()).isNull();
        assertThat(context.clientBinding()).isEqualTo(binding);
        assertThat(context.sourceBinding().protocol()).isEqualTo(ClientProjectBinding.PROTOCOL);
        assertThat(context.protectedToolIds()).containsExactly(tool);
    }

    @Test void 两种项目来源不能同时存在() {
        var local = new LocalProjectBinding(UUID.randomUUID(), "owner", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "b".repeat(64), "node", 1, Instant.now());
        var client = new ClientProjectBinding(UUID.randomUUID(), "owner", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), 1, Instant.now());

        assertThatThrownBy(() -> new ProjectRuntimeContext(local, client, Set.of(),
                OperationAuthorizationMode.ASK_ON_EFFECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能绑定一种来源");
    }
}
