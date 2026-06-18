package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;

import java.util.List;
import java.util.UUID;

public class DefaultToolAuthorizationPolicy implements ToolAuthorizationPolicy {

    @Override
    public AuthorizationDecision check(PrincipalRef principal, UUID agentId, ToolDefinition tool, List<ToolGrant> grants) {
        if (!principal.tenantId().equals(tool.tenantId())) {
            return AuthorizationDecision.deny("工具不属于当前租户");
        }

        if (!tool.enabled()) {
            return AuthorizationDecision.deny("工具已禁用 " + tool.name());
        }

        boolean granted = grants.stream().anyMatch(grant ->
                grant.granted()
                        && grant.tenantId().equals(principal.tenantId())
                        && grant.toolId().equals(tool.id())
                        && grant.agentId().equals(agentId)
        );

        if (!granted) {
            return AuthorizationDecision.deny("Agent 未获得工具授权 " + tool.name());
        }

        return AuthorizationDecision.allow();
    }
}
