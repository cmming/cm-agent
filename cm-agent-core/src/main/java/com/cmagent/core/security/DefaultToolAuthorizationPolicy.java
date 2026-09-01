package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;

import java.util.List;
import java.util.UUID;

/**
 * 按租户归属、启用状态和 Agent 授权记录执行默认工具授权校验。
 *
 * <p>三步校验顺序不可调整：先拒绝跨租户读取，再拒绝调用已停用工具，最后核对 Agent 级
 * {@code ToolGrant}。任何一步失败立即返回受控中文拒绝原因，供审计与对外提示使用。</p>
 */
public class DefaultToolAuthorizationPolicy implements ToolAuthorizationPolicy {

    /**
     * 校验工具的租户归属、启用状态以及 Agent 级授权记录。
     *
     * <p>{@code principal} 必须来自认证上下文，工具和授权记录必须来自当前租户查询结果；
     * 模型提交的任何信息都不参与授权判断。</p>
     *
     * @param principal 当前认证主体
     * @param agentId 目标 Agent 标识
     * @param tool 待调用的工具定义
     * @param grants 当前租户内该工具的授权记录
     * @return 全部检查通过时允许；任一步失败返回带受控原因的拒绝
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
