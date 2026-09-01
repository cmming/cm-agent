package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;

import java.util.List;
import java.util.UUID;

/**
 * 定义 Agent 调用工具前的租户、状态和授权校验契约。
 *
 * <p>授权判断每次调用都必须重新执行，不能依赖运行前筛选结果缓存：工具可能在运行期间被
 * 禁用、取消授权或变更租户归属。实现方必须按“同租户 → 已启用 → Agent 级授权”的顺序检查，
 * 拒绝原因会进入审计，不得携带敏感信息。</p>
 */
public interface ToolAuthorizationPolicy {

    /**
     * 执行授权条件校验并返回允许或拒绝决定。
     *
     * @param principal 当前认证主体，来自认证上下文而非模型输出
     * @param agentId 目标 Agent 标识，须与运行请求一致
     * @param tool 待调用的工具定义
     * @param grants 当前租户内该工具可用的授权记录
     * @return 允许或拒绝的授权决定
     */
    AuthorizationDecision check(PrincipalRef principal, UUID agentId, ToolDefinition tool, List<ToolGrant> grants);
}
