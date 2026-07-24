package com.cmagent.server.web;

import com.cmagent.server.security.JwtService;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.BootstrapAdminProperties;
import com.cmagent.server.security.CurrentUserResponse;
import com.cmagent.server.security.LoginRequest;
import com.cmagent.server.security.LoginResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
/** 登录和当前用户信息接口，不在响应中暴露任何敏感配置。 */
public class AuthController {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final List<String> PERMISSIONS = List.of(
            "agent:run",
            "agent:read",
            "agent:write",
            "tool:read",
            "tool:grant",
            "tool:debug",
            "tool:mcp:invoke",
            "audit:read",
            "apikey:write"
    );

    private final JwtService jwtService;
    private final BootstrapAdminProperties bootstrapAdminProperties;
    private final AuditAppender auditAppender;
    /**
     * AuthController：处理该类内部的业务逻辑或辅助计算。
     */
    public AuthController(JwtService jwtService, BootstrapAdminProperties bootstrapAdminProperties, AuditAppender auditAppender) {
        this.jwtService = jwtService;
        this.bootstrapAdminProperties = bootstrapAdminProperties;
        this.auditAppender = auditAppender;
    }

    /**
     * 校验登录凭据并签发访问令牌。
     *
     * @param request 用户名和密码
     * @return 访问令牌及当前主体信息
     * @throws ResponseStatusException 凭据无效或 bootstrap admin 未启用时抛出
     */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        String username = principalFrom(request);
        String password = request == null || request.password() == null ? "" : request.password();
        if (!bootstrapAdminProperties.isBootstrapAdminEnabled()) {
            auditLogin(username, "FAILED", "bootstrap admin 未启用");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bootstrap admin 未启用");
        }
        if (!bootstrapAdminProperties.getBootstrapAdminUsername().equals(username)
                || !bootstrapAdminProperties.getBootstrapAdminPassword().equals(password)) {
            auditLogin(username, "FAILED", "用户名或密码错误");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }

        String configuredUsername = bootstrapAdminProperties.getBootstrapAdminUsername();
        String displayName = bootstrapAdminProperties.getBootstrapAdminDisplayName();
        String token = jwtService.createToken(TENANT_ID, configuredUsername, displayName, PERMISSIONS);
        auditLogin(configuredUsername, "SUCCEEDED", "登录成功");
        return new LoginResponse(TENANT_ID.toString(), configuredUsername, displayName, PERMISSIONS, token);
    }

    /**
     * 返回当前 JWT 主体的非敏感信息。
     *
     * @return 当前用户信息
     * @throws ResponseStatusException 请求未携带有效认证主体时抛出
     */
    @GetMapping("/me")
    public CurrentUserResponse me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new CurrentUserResponse(
                session.tenantId().toString(),
                session.principalId(),
                session.displayName(),
                session.permissions()
        );
    }

    /**
     * principalFrom：处理该类内部的业务逻辑或辅助计算。
     */
    private String principalFrom(LoginRequest request) {
        if (request == null || request.username() == null || request.username().isBlank()) {
            return "anonymous";
        }
        return request.username();
    }

    /**
     * auditLogin：处理该类内部的业务逻辑或辅助计算。
     */
    private void auditLogin(String principalId, String status, String message) {
        auditAppender.append(
                TENANT_ID,
                principalId,
                "LOGIN",
                "AUTH",
                "bootstrap-admin",
                status,
                message
        );
    }
}
