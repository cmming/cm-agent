package com.cmagent.core.domain;

import com.cmagent.api.PrincipalRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunRequestTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Test
    /**
     * 验证 {@code MissingRunId} 异常场景会被正确拒绝。
     */
    void rejectsMissingRunId() {
        assertThatThrownBy(() -> new AgentRunRequest(
                null, TENANT_ID, agent(TENANT_ID), model(TENANT_ID), principal(TENANT_ID), "你好", List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    /**
     * 验证 {@code CrossTenantAgent} 异常场景会被正确拒绝。
     */
    void rejectsCrossTenantAgent() {
        UUID anotherTenantId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertThatThrownBy(() -> new AgentRunRequest(
                RUN_ID, TENANT_ID, agent(anotherTenantId), model(TENANT_ID), principal(TENANT_ID), "你好", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Agent 不属于当前租户");
    }

    @Test
    /**
     * 验证 {@code CrossTenantModelConfig} 异常场景会被正确拒绝。
     */
    void rejectsCrossTenantModelConfig() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AgentDefinition agent = agent(tenantId);
        ModelConfig model = model(UUID.fromString("00000000-0000-0000-0000-000000000002"));

        assertThatThrownBy(() -> new AgentRunRequest(
                UUID.randomUUID(), tenantId, agent, model, principal(tenantId), "你好", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("模型配置不属于当前租户");
    }

    @Test
    /**
     * 验证 {@code ModelConfigNotBoundToAgent} 异常场景会被正确拒绝。
     */
    void rejectsModelConfigNotBoundToAgent() {
        UUID anotherModelId = UUID.fromString("00000000-0000-0000-0000-000000000402");
        ModelConfig anotherModel = new ModelConfig(
                anotherModelId, TENANT_ID, ModelProviderType.OPENAI_COMPATIBLE,
                "其他模型", "https://example.invalid/v1", "other-model", true);

        assertThatThrownBy(() -> new AgentRunRequest(
                RUN_ID, TENANT_ID, agent(TENANT_ID), anotherModel, principal(TENANT_ID), "你好", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("模型配置与 Agent 绑定不一致");
    }

    @Test
    /**
     * 验证 {@code MissingAgentModelProviderIdWithClassifiedMessage} 异常场景会被正确拒绝。
     */
    void rejectsMissingAgentModelProviderIdWithClassifiedMessage() {
        AgentDefinition agent = new AgentDefinition(
                AGENT_ID, TENANT_ID, "企业助手", "", "你是企业助手", null,
                "agent-model", 0.2, 5, true, List.of(), "tester", "tester");

        assertThatThrownBy(() -> new AgentRunRequest(
                RUN_ID, TENANT_ID, agent, model(TENANT_ID), principal(TENANT_ID), "你好", List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Agent 模型配置 ID 不能为空");
    }

    @Test
    /**
     * 验证 {@code MissingModelConfigIdWithClassifiedMessage} 异常场景会被正确拒绝。
     */
    void rejectsMissingModelConfigIdWithClassifiedMessage() {
        ModelConfig model = new ModelConfig(
                null, TENANT_ID, ModelProviderType.OPENAI_COMPATIBLE,
                "测试模型", "https://example.invalid/v1", "default-model", true);

        assertThatThrownBy(() -> new AgentRunRequest(
                RUN_ID, TENANT_ID, agent(TENANT_ID), model, principal(TENANT_ID), "你好", List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("模型配置 ID 不能为空");
    }

    @Test
    /**
     * 验证 {@code CrossTenantPrincipal} 异常场景会被正确拒绝。
     */
    void rejectsCrossTenantPrincipal() {
        UUID anotherTenantId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertThatThrownBy(() -> new AgentRunRequest(
                RUN_ID, TENANT_ID, agent(TENANT_ID), model(TENANT_ID), principal(anotherTenantId), "你好", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("调用主体不属于当前租户");
    }

    @Test
    /**
     * 验证 {@code CrossTenantTool} 异常场景会被正确拒绝。
     */
    void rejectsCrossTenantTool() {
        UUID anotherTenantId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertThatThrownBy(() -> new AgentRunRequest(
                RUN_ID, TENANT_ID, agent(TENANT_ID), model(TENANT_ID), principal(TENANT_ID),
                "你好", List.of(tool(anotherTenantId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("工具不属于当前租户");
    }

    @Test
    /**
     * 验证或支持 {@code copiesAuthorizedTools} 所描述的测试场景。
     */
    void copiesAuthorizedTools() {
        List<ToolDefinition> tools = new ArrayList<>(List.of(tool(TENANT_ID)));
        AgentRunRequest request = new AgentRunRequest(
                RUN_ID, TENANT_ID, agent(TENANT_ID), model(TENANT_ID), principal(TENANT_ID), "你好", tools);
        tools.clear();
        assertThat(request.tools()).hasSize(1);
        assertThatThrownBy(() -> request.tools().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    /**
     * 验证对外暴露的 {@code AgentIdFromDefinition}。
     */
    void exposesAgentIdFromDefinition() {
        AgentRunRequest request = new AgentRunRequest(
                RUN_ID, TENANT_ID, agent(TENANT_ID), model(TENANT_ID), principal(TENANT_ID), "你好", List.of());

        assertThat(request.agentId()).isEqualTo(AGENT_ID);
    }

    /**
     * 构造测试 Agent 定义。
     *
     * @param tenantId 测试租户标识
     */
    private static AgentDefinition agent(UUID tenantId) {
        return new AgentDefinition(
                AGENT_ID, tenantId, "企业助手", "", "你是企业助手", MODEL_ID,
                "agent-model", 0.2, 5, true, List.of(), "tester", "tester");
    }

    /**
     * 构造测试模型配置。
     *
     * @param tenantId 测试租户标识
     */
    private static ModelConfig model(UUID tenantId) {
        return new ModelConfig(
                MODEL_ID, tenantId, ModelProviderType.OPENAI_COMPATIBLE,
                "测试模型", "https://example.invalid/v1", "default-model", true);
    }

    /**
     * 构造测试认证主体。
     *
     * @param tenantId 测试租户标识
     */
    private static PrincipalRef principal(UUID tenantId) {
        return new PrincipalRef(tenantId, "principal", "测试主体", Set.of("agent:run"));
    }

    /**
     * 构造测试工具定义。
     *
     * @param tenantId 测试租户标识
     */
    private static ToolDefinition tool(UUID tenantId) {
        return new ToolDefinition(
                TOOL_ID, tenantId, "echo", "回显", ToolType.LOCAL,
                "{\"type\":\"object\"}", ToolRiskLevel.LOW, true, "", "tester", "tester");
    }
}
