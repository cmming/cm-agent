package com.cmagent.core.runtime;

import com.cmagent.api.PrincipalRef;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolInvocationRequestTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Test
    void rejectsCrossTenantPrincipal() {
        UUID anotherTenantId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        PrincipalRef principal = new PrincipalRef(
                anotherTenantId, "principal", "测试主体", Set.of("agent:run"));

        assertThatThrownBy(() -> new ToolInvocationRequest(
                TENANT_ID, AGENT_ID, principal, RUN_ID, "tool-call-1", TOOL_ID, "echo", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("调用主体不属于当前租户");
    }

    @Test
    void rejectsBlankToolCallId() {
        PrincipalRef principal = new PrincipalRef(
                TENANT_ID, "principal", "测试主体", Set.of("agent:run"));

        assertThatThrownBy(() -> new ToolInvocationRequest(
                TENANT_ID, AGENT_ID, principal, RUN_ID, "  ", TOOL_ID, "echo", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolCallId 不能为空");
    }

    @Test
    void rejectsBlankToolName() {
        PrincipalRef principal = new PrincipalRef(
                TENANT_ID, "principal", "测试主体", Set.of("agent:run"));

        assertThatThrownBy(() -> new ToolInvocationRequest(
                TENANT_ID, AGENT_ID, principal, RUN_ID, "tool-call-1", TOOL_ID, "  ", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolName 不能为空");
    }

    @Test
    void retainsCompleteInvocationContext() {
        PrincipalRef principal = new PrincipalRef(
                TENANT_ID, "principal", "测试主体", Set.of("agent:run"));

        ToolInvocationRequest request = new ToolInvocationRequest(
                TENANT_ID, AGENT_ID, principal, RUN_ID, "tool-call-1", TOOL_ID, "echo", "{\"text\":\"你好\"}");

        assertThat(request.tenantId()).isEqualTo(TENANT_ID);
        assertThat(request.agentId()).isEqualTo(AGENT_ID);
        assertThat(request.principal()).isEqualTo(principal);
        assertThat(request.runId()).isEqualTo(RUN_ID);
        assertThat(request.toolCallId()).isEqualTo("tool-call-1");
        assertThat(request.toolId()).isEqualTo(TOOL_ID);
        assertThat(request.toolName()).isEqualTo("echo");
        assertThat(request.inputJson()).isEqualTo("{\"text\":\"你好\"}");
    }
}
