package com.cmagent.core.security;

/**
 * 表示权限或工具授权校验的允许状态及其原因。
 */
public record AuthorizationDecision(boolean allowed, String reason) {

    /**
     * 创建允许访问的授权决定。
     *
     * @return 允许访问的决定
     */
    public static AuthorizationDecision allow() {
        return new AuthorizationDecision(true, "允许访问");
    }

    /**
     * 创建包含拒绝原因的授权决定。
      *
     * @param reason 拒绝访问的原因
     * @return 拒绝访问的决定
     */
    public static AuthorizationDecision deny(String reason) {
        return new AuthorizationDecision(false, reason);
    }
}
