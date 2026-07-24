package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;

/**
 * PermissionEvaluator 的核心领域类型。
 */
public interface PermissionEvaluator {

    /**
     * 定义 check 操作。
     */
    AuthorizationDecision check(PrincipalRef principal, String permission);
}
