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
public class AuthController {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final List<String> PERMISSIONS = List.of(
            "agent:run",
            "agent:read",
            "agent:write",
            "tool:read",
            "tool:grant",
            "audit:read",
            "apikey:write"
    );

    private final JwtService jwtService;
    private final BootstrapAdminProperties bootstrapAdminProperties;
    private final AuditAppender auditAppender;

    public AuthController(JwtService jwtService, BootstrapAdminProperties bootstrapAdminProperties, AuditAppender auditAppender) {
        this.jwtService = jwtService;
        this.bootstrapAdminProperties = bootstrapAdminProperties;
        this.auditAppender = auditAppender;
    }

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

    private String principalFrom(LoginRequest request) {
        if (request == null || request.username() == null || request.username().isBlank()) {
            return "anonymous";
        }
        return request.username();
    }

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
