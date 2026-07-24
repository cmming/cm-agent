package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;

import java.util.List;
import java.util.UUID;

/**
 * ToolAuthorizationPolicy 的核心领域类型。
 */
public interface ToolAuthorizationPolicy {

    /**
     * 定义 check 操作。
     */
    AuthorizationDecision check(PrincipalRef principal, UUID agentId, ToolDefinition tool, List<ToolGrant> grants);
}
