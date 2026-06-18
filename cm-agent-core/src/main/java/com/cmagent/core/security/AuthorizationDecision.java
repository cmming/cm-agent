package com.cmagent.core.security;

public record AuthorizationDecision(boolean allowed, String reason) {

    public static AuthorizationDecision allow() {
        return new AuthorizationDecision(true, "允许访问");
    }

    public static AuthorizationDecision deny(String reason) {
        return new AuthorizationDecision(false, reason);
    }
}
