package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPermissionEvaluatorTest {

    @Test
    void allowWhenPrincipalHasPermission() {
        PrincipalRef principal = new PrincipalRef(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "admin",
                "管理员",
                Set.of("agent:run")
        );

        AuthorizationDecision decision = new DefaultPermissionEvaluator().check(principal, "agent:run");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo("允许访问");
    }

    @Test
    void denyWhenPrincipalMissesPermission() {
        PrincipalRef principal = new PrincipalRef(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "viewer",
                "只读用户",
                Set.of("agent:read")
        );

        AuthorizationDecision decision = new DefaultPermissionEvaluator().check(principal, "agent:run");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("缺少权限 agent:run");
    }
}
