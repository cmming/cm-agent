package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;

/**
 * 使用主体携带的权限集合完成默认权限判断。
 *
 * <p>只做集合包含判断，不做任何租户或资源归属校验；租户边界由调用方在仓储与编排层负责。
 * 权限集合来自认证主体，查询不到的权限一律拒绝。</p>
 */
public class DefaultPermissionEvaluator implements PermissionEvaluator {

    /**
     * 判断当前主体是否持有指定权限编码。
     *
     * @param principal 当前认证主体，其权限集合来自认证服务而非请求输入
     * @param permission 待校验的权限编码
     * @return 持有时允许；否则拒绝并携带“缺少权限”原因
     */
    @Override
    public AuthorizationDecision check(PrincipalRef principal, String permission) {
        if (principal.permissions().contains(permission)) {
            return AuthorizationDecision.allow();
        }
        return AuthorizationDecision.deny("缺少权限 " + permission);
    }
}
