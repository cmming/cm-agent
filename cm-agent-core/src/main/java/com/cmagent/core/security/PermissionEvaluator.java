package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;

/**
 * 定义根据认证主体和权限编码生成授权决定的契约。
 *
 * <p>权限编码来自认证服务随主体下发的权限集合，不由客户端请求提供；实现方不得在判定中引入
 * 客户端可控输入，避免越权。拒绝结果必须携带稳定、可对外展示的原因文本。</p>
 */
public interface PermissionEvaluator {

    /**
     * 执行授权条件校验并返回允许或拒绝决定。
     *
     * @param principal 当前认证主体，携带服务端下发的权限集合
     * @param permission 待校验的权限编码
     * @return 允许或拒绝的授权决定
     */
    AuthorizationDecision check(PrincipalRef principal, String permission);
}
