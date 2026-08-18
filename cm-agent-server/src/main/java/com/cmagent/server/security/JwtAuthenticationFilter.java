package com.cmagent.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
/** 从 Bearer Token 或控制台 HttpOnly Cookie 构建 Spring Security 认证主体，失败时保持请求未认证。 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    /**
     * 创建 {@code JwtAuthenticationFilter} 实例并保存其运行所需依赖。
     *
     * @param jwtService 负责当前业务流程的服务。
     */
    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    /**
     * 解析 JWT，并把可信会话主体写入安全上下文。
     *
     * <p>显式 {@code Authorization} 头优先级最高。只要客户端发送了该头，过滤器就不会
     * 回退到 Cookie，避免损坏或恶意 Bearer 值被浏览器旧会话静默掩盖。</p>
     *
     * @param request 当前 HTTP 请求，用于读取 Bearer 令牌
     * @param response 当前 HTTP 响应。
     * @param filterChain 继续执行后续安全过滤器的调用链
     */
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = authenticationToken(request);
        if (token != null) {
            try {
                JwtService.JwtSession session = jwtService.parseAndVerify(token);
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        session,
                        token,
                        session.permissions().stream().map(SimpleGrantedAuthority::new).toList()
                );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ex) {
                SecurityContextHolder.clearContext();
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 按显式 Bearer、控制台 Cookie 的顺序选择唯一认证来源。
     */
    private String authenticationToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            return authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        }
        return ConsoleSessionCookie.tokenOf(request);
    }
}
