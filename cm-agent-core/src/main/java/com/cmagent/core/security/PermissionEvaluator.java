package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;

public interface PermissionEvaluator {

    AuthorizationDecision check(PrincipalRef principal, String permission);
}
