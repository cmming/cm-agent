package com.cmagent.server.web;

import com.cmagent.server.security.JwtService;
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
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin123456";
    private static final String DISPLAY_NAME = "系统管理员";
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

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        if (!USERNAME.equals(request.username()) || !PASSWORD.equals(request.password())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        String token = jwtService.createToken(TENANT_ID, USERNAME, DISPLAY_NAME, PERMISSIONS);
        return new LoginResponse(TENANT_ID.toString(), USERNAME, DISPLAY_NAME, PERMISSIONS, token);
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
}
