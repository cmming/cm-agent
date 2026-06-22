package com.cmagent.core.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDefinitionTest {

    @Test
    void createEnabledAgentWithTenantAndTools() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID toolId = UUID.fromString("00000000-0000-0000-0000-000000000101");

        AgentDefinition agent = new AgentDefinition(
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                tenantId,
                "企业助手",
                "回答企业内部问题",
                "你是企业业务助手，回答必须简洁并记录工具调用。",
                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                "qwen-max",
                0.2,
                6,
                true,
                List.of(toolId),
                "admin",
                "admin"
        );

        assertThat(agent.tenantId()).isEqualTo(tenantId);
        assertThat(agent.enabled()).isTrue();
        assertThat(agent.toolIds()).containsExactly(toolId);
    }
}
