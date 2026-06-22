package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;

public class DefaultPermissionEvaluator implements PermissionEvaluator {

    @Override
    public AuthorizationDecision check(PrincipalRef principal, String permission) {
        if (principal.permissions().contains(permission)) {
            return AuthorizationDecision.allow();
        }
        return AuthorizationDecision.deny("缺少权限 " + permission);
    }
}
