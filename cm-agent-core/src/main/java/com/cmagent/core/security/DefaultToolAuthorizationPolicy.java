package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;

import java.util.List;
import java.util.UUID;

/**
 * DefaultToolAuthorizationPolicy 的核心领域类型。
 */
public class DefaultToolAuthorizationPolicy implements ToolAuthorizationPolicy {

    /**
     * 执行 check 操作。
     */
    /**
     * 定义 check 操作。
     */
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
                        // The first slice authorizes tools at agent scope; roleCode is optional metadata.
                        && grant.agentId().equals(agentId)
        );

        if (!granted) {
            return AuthorizationDecision.deny("Agent 未获得工具授权 " + tool.name());
        }

        return AuthorizationDecision.allow();
    }
}
