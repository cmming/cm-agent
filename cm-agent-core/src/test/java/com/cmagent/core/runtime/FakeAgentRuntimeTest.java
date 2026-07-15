package com.cmagent.core.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.RunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FakeAgentRuntimeTest {

    @Test
    void echoRunInputForDeterministicTests() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AgentRunRequest request = new AgentRunRequest(
                tenantId,
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                new AgentDefinition(UUID.fromString("00000000-0000-0000-0000-000000000201"), tenantId, "测试 Agent", "", "", null, "test", 0.2, 3, true, List.of(), "admin", "admin"),
                new PrincipalRef(tenantId, "admin", "管理员", Set.of("agent:run")),
                "请查询今天日程",
                List.of()
        );

        var result = new FakeAgentRuntime().run(request);

        assertThat(result.runId()).isNotNull();
        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.output()).isEqualTo("fake-runtime: 请查询今天日程");
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.startedAt()).isEqualTo(result.finishedAt());
        assertThat(result.errorMessage()).isEmpty();
    }
}
