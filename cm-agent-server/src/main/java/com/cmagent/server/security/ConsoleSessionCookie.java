package com.cmagent.server.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

/**
 * 管理控制台跨页面认证 Cookie 的统一属性和读取边界。
 *
 * <p>Cookie 仅作用于 {@code /api}，并使用 {@code HttpOnly} 与
 * {@code SameSite=Strict}，避免前端脚本读取 JWT，同时阻止浏览器在跨站请求中携带它。
 * 本地 HTTP 环境无法使用 {@code Secure}，HTTPS 部署时则根据请求协议自动启用。</p>
 */
public final class ConsoleSessionCookie {
    public static final String NAME = "CM_AGENT_CONSOLE_SESSION";
    private static final String API_PATH = "/api";

    private ConsoleSessionCookie() {
    }

    /**
     * 创建仅在当前浏览器会话内有效的控制台认证 Cookie。
     *
     * @param token 已签名的访问令牌
     * @param secure 当前请求是否通过 HTTPS 到达应用
     * @return 包含统一安全属性的响应 Cookie
     */
    public static ResponseCookie active(String token, boolean secure) {
        return base(token, secure).build();
    }

    /**
     * 创建用于退出登录的过期 Cookie；路径和安全属性必须与签发时保持一致。
     *
     * @param secure 当前请求是否通过 HTTPS 到达应用
     * @return 立即过期的响应 Cookie
     */
    public static ResponseCookie expired(boolean secure) {
        return base("", secure).maxAge(0).build();
    }

    /**
     * 从请求中读取控制台 Cookie。只有 JWT 过滤器会把该值作为认证候选，业务代码不得直接信任。
     *
     * @param request 当前 HTTP 请求
     * @return Cookie 中的令牌；不存在时返回 {@code null}
     */
    public static String tokenOf(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value, boolean secure) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(API_PATH);
    }
}
