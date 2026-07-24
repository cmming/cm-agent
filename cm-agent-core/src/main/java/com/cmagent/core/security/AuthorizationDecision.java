package com.cmagent.core.security;

/**
 * AuthorizationDecision 的核心领域类型。
 */
public record AuthorizationDecision(boolean allowed, String reason) {

    /**
     * 执行 allow 操作。
     */
    public static AuthorizationDecision allow() {
        return new AuthorizationDecision(true, "允许访问");
    }

    /**
     * 执行 deny 操作。
     */
    public static AuthorizationDecision deny(String reason) {
        return new AuthorizationDecision(false, reason);
    }
}
