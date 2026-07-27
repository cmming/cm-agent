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
/** 从 Bearer Token 构建 Spring Security 认证主体，失败时保持请求未认证。 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    /**
     * JwtAuthenticationFilter：处理该类内部的业务逻辑或辅助计算。
     *
     * @param jwtService 参与 JwtAuthenticationFilter 处理的 jwtService 输入值。
     */
    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    /**
     * doFilterInternal：处理该类内部的业务逻辑或辅助计算。
     *
     * @param request 当前业务请求参数，承载调用方提交的数据。
     * @param response 参与 doFilterInternal 处理的 response 输入值。
     * @param filterChain 参与 doFilterInternal 处理的 filterChain 输入值。
     */
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
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
}
