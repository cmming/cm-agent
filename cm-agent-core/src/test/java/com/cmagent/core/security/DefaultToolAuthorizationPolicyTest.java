package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultToolAuthorizationPolicyTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Test
    void allowEnabledGrantedToolInSameTenant() {
        ToolDefinition tool = new ToolDefinition(
                TOOL_ID,
                TENANT_ID,
                "calendar.query",
                "查询日程",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
                "admin",
                "admin"
        );
        ToolGrant grant = new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, true);
        PrincipalRef principal = new PrincipalRef(
                TENANT_ID,
                "admin",
                "管理员",
                Set.of("agent:run")
        );

        AuthorizationDecision decision = new DefaultToolAuthorizationPolicy().check(principal, AGENT_ID, tool, List.of(grant));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo("允许访问");
    }

    @Test
    void denyWhenToolGrantIsMissing() {
        ToolDefinition tool = new ToolDefinition(
                TOOL_ID,
                TENANT_ID,
                "calendar.query",
                "查询日程",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
                "admin",
                "admin"
        );
        PrincipalRef principal = new PrincipalRef(
                TENANT_ID,
                "admin",
                "管理员",
                Set.of("agent:run")
        );

        AuthorizationDecision decision = new DefaultToolAuthorizationPolicy().check(principal, AGENT_ID, tool, List.of());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("Agent 未获得工具授权 calendar.query");
    }

    @Test
    void denyWhenToolBelongsToAnotherTenant() {
        ToolDefinition tool = new ToolDefinition(
                TOOL_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "calendar.query",
                "查询日程",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
                "admin",
                "admin"
        );
        ToolGrant grant = new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, true);
        PrincipalRef principal = new PrincipalRef(
                TENANT_ID,
                "admin",
                "管理员",
                Set.of("agent:run")
        );

        AuthorizationDecision decision = new DefaultToolAuthorizationPolicy().check(principal, AGENT_ID, tool, List.of(grant));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("工具不属于当前租户");
    }

    @Test
    void denyWhenToolIsDisabled() {
        ToolDefinition tool = new ToolDefinition(
                TOOL_ID,
                TENANT_ID,
                "calendar.query",
                "查询日程",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                false,
                "",
                "admin",
                "admin"
        );
        ToolGrant grant = new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, true);
        PrincipalRef principal = new PrincipalRef(
                TENANT_ID,
                "admin",
                "管理员",
                Set.of("agent:run")
        );

        AuthorizationDecision decision = new DefaultToolAuthorizationPolicy().check(principal, AGENT_ID, tool, List.of(grant));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("工具已禁用 calendar.query");
    }

    @Test
    void denyWhenMatchingGrantIsNotGranted() {
        ToolDefinition tool = new ToolDefinition(
                TOOL_ID,
                TENANT_ID,
                "calendar.query",
                "查询日程",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
                "admin",
                "admin"
        );
        ToolGrant grant = new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, false);
        PrincipalRef principal = new PrincipalRef(
                TENANT_ID,
                "admin",
                "管理员",
                Set.of("agent:run")
        );

        AuthorizationDecision decision = new DefaultToolAuthorizationPolicy().check(principal, AGENT_ID, tool, List.of(grant));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("Agent 未获得工具授权 calendar.query");
    }

    @Test
    void allowGrantWithRoleCodeMetadataForCurrentAgent() {
        ToolDefinition tool = new ToolDefinition(
                TOOL_ID,
                TENANT_ID,
                "calendar.query",
                "查询日程",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
                "admin",
                "admin"
        );
        ToolGrant grant = new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, "ops-admin", true);
        PrincipalRef principal = new PrincipalRef(
                TENANT_ID,
                "admin",
                "管理员",
                Set.of("agent:run")
        );

        AuthorizationDecision decision = new DefaultToolAuthorizationPolicy().check(principal, AGENT_ID, tool, List.of(grant));

        assertThat(decision.allowed()).isTrue();
    }
}
