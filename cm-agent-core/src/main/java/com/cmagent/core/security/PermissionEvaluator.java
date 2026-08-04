package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;

/**
 * 定义根据认证主体和权限编码生成授权决定的契约。
 */
public interface PermissionEvaluator {

    /**
     * 执行授权条件校验并返回允许或拒绝决定。
      *
      * @param principal 当前认证主体
      * @param permission 待校验的权限编码
     */
    AuthorizationDecision check(PrincipalRef principal, String permission);
}
