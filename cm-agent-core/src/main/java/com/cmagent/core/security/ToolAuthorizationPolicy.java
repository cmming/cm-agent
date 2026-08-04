package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;

import java.util.List;
import java.util.UUID;

/**
 * 定义 Agent 调用工具前的租户、状态和授权校验契约。
 */
public interface ToolAuthorizationPolicy {

    /**
     * 执行授权条件校验并返回允许或拒绝决定。
      *
      * @param principal 当前认证主体
      * @param agentId 目标 Agent 标识
      * @param tool 当前工具定义
      * @param grants 工具授权集合
     */
    AuthorizationDecision check(PrincipalRef principal, UUID agentId, ToolDefinition tool, List<ToolGrant> grants);
}
