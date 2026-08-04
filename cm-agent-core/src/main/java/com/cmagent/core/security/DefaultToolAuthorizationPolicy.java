package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;

import java.util.List;
import java.util.UUID;

/**
 * 按租户归属、启用状态和 Agent 授权记录执行默认工具授权校验。
 */
public class DefaultToolAuthorizationPolicy implements ToolAuthorizationPolicy {

    /**
     * 校验工具的租户归属、启用状态以及 Agent 级授权记录。
     *
     * @param principal 当前认证主体
     * @param agentId 目标 Agent 标识
     * @param tool 当前工具定义
     * @param grants 工具授权集合
     * @return 允许或拒绝的授权决定
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
                        // 当前授权粒度为 Agent；roleCode 仅作为可选元数据，不参与本次匹配。
                        && grant.agentId().equals(agentId)
        );

        if (!granted) {
            return AuthorizationDecision.deny("Agent 未获得工具授权 " + tool.name());
        }

        return AuthorizationDecision.allow();
    }
}
