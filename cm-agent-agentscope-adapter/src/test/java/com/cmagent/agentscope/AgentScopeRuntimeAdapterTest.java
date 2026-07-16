package com.cmagent.agentscope;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.runtime.AgentRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeRuntimeAdapterTest {

    @Test
    void mapCmAgentRequestToAgentScopeRunSpec() {
        UUID tenantId = tenantId();
        UUID agentId = agentId();
        AgentRunRequest request = request(tenantId, agentId);

        AgentScopeRunSpec spec = new AgentScopeRuntimeAdapter().toRunSpec(request);

        assertThat(spec.tenantId()).isEqualTo(tenantId.toString());
        assertThat(spec.agentId()).isEqualTo(agentId.toString());
        assertThat(spec.userInput()).isEqualTo("查询客户状态");
        assertThat(spec.principalId()).isEqualTo("admin");
    }

    @Test
    void runReturnsControlledFailureUntilAgentScopeBridgeIsEnabled() {
        UUID tenantId = tenantId();
        UUID agentId = agentId();
        AgentRunRequest request = request(tenantId, agentId);
        AgentRuntime runtime = new AgentScopeRuntimeAdapter();

        AgentRunResult result = runtime.run(request);

        assertThat(result.runId()).isNotNull();
        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.output()).isEmpty();
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.startedAt()).isNotNull();
        assertThat(result.finishedAt()).isNotNull();
        assertThat(result.errorMessage())
                .isEqualTo("AgentScope 真实运行桥接尚未启用，已生成受控运行规格 " + agentId);
    }

    private static AgentRunRequest request(UUID tenantId, UUID agentId) {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID modelId = UUID.fromString("00000000-0000-0000-0000-000000000401");
        AgentDefinition agent = new AgentDefinition(
                agentId, tenantId, "企业助手", "", "你是企业助手", modelId,
                "agent-model", 0.2, 5, true, List.of(), "tester", "tester");
        ModelConfig modelConfig = new ModelConfig(
                modelId, tenantId, ModelProviderType.OPENAI_COMPATIBLE,
                "测试模型", "https://example.invalid/v1", "default-model", true);
        return new AgentRunRequest(
                runId,
                tenantId,
                agent,
                modelConfig,
                new PrincipalRef(tenantId, "admin", "系统管理员", Set.of("agent:run")),
                "查询客户状态",
                List.of()
        );
    }

    private static UUID tenantId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private static UUID agentId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000201");
    }
}
