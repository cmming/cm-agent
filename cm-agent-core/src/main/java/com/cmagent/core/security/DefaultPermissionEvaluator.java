package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;

/**
 * 使用主体携带的权限集合完成默认权限判断。
 */
public class DefaultPermissionEvaluator implements PermissionEvaluator {

    /**
     * 判断当前主体是否持有指定权限编码。
     *
     * @param principal 当前认证主体
     * @param permission 待校验的权限编码
     * @return 允许或拒绝的授权决定
     */
    @Override
    public AuthorizationDecision check(PrincipalRef principal, String permission) {
        if (principal.permissions().contains(permission)) {
            return AuthorizationDecision.allow();
        }
        return AuthorizationDecision.deny("缺少权限 " + permission);
    }
}
