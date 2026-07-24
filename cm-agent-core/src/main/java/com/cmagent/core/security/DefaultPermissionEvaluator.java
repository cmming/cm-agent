package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;

/**
 * DefaultPermissionEvaluator 的核心领域类型。
 */
public class DefaultPermissionEvaluator implements PermissionEvaluator {

    /**
     * 执行 check 操作。
     */
    /**
     * 定义 check 操作。
     */
    @Override
    public AuthorizationDecision check(PrincipalRef principal, String permission) {
        if (principal.permissions().contains(permission)) {
            return AuthorizationDecision.allow();
        }
        return AuthorizationDecision.deny("缺少权限 " + permission);
    }
}
