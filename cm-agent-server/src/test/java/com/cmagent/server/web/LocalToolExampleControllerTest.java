package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.local.MysqlLocalExampleCatalog;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.LocalToolExampleSummary;
import com.cmagent.server.service.MysqlLocalExampleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocalToolExampleControllerTest {
    private PermissionEvaluator permissions;
    private AuditAppender auditAppender;
    private MysqlLocalExampleService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        permissions = mock(PermissionEvaluator.class);
        auditAppender = mock(AuditAppender.class);
        service = mock(MysqlLocalExampleService.class);
        mvc = MockMvcBuilders.standaloneSetup(new LocalToolExampleController(permissions, auditAppender, service)).build();
    }

    @Test
    void 查询和安装分别要求读取与授权权限并记录拒绝审计() throws Exception {
        when(permissions.check(any(PrincipalRef.class), eq("tool:read"))).thenReturn(AuthorizationDecision.deny("缺少读取权限"));

        mvc.perform(get("/api/tools/local-examples").principal(authentication()))
                .andExpect(status().isForbidden());
        verify(auditAppender).accessDenied(any(PrincipalRef.class), eq("TOOL"), eq("local-examples"),
                eq("tool:read"), anyString());

        when(permissions.check(any(PrincipalRef.class), eq("tool:grant"))).thenReturn(AuthorizationDecision.deny("缺少授权权限"));
        mvc.perform(post("/api/tools/local-examples/echo").principal(authentication("tool:read")))
                .andExpect(status().isForbidden());
        verify(auditAppender).accessDenied(any(PrincipalRef.class), eq("TOOL"), eq("echo"),
                eq("tool:grant"), anyString());
    }

    @Test
    void 具备权限时返回目录并安装固定工具() throws Exception {
        allow();
        when(service.list(any())).thenReturn(List.of(summary(false)));
        when(service.install(any(), eq("echo"))).thenReturn(summary(true));

        mvc.perform(get("/api/tools/local-examples").principal(authentication("tool:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("echo"))
                .andExpect(jsonPath("$[0].installed").value(false));
        mvc.perform(post("/api/tools/local-examples/echo").principal(authentication("tool:grant"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed").value(true));
    }

    private void allow() {
        when(permissions.check(any(PrincipalRef.class), anyString())).thenReturn(AuthorizationDecision.allow());
    }

    private static UsernamePasswordAuthenticationToken authentication(String... permissions) {
        JwtService.JwtSession session = new JwtService.JwtSession(MysqlLocalExampleCatalog.EXAMPLE_TENANT_ID,
                "admin", "管理员", List.of(permissions));
        return new UsernamePasswordAuthenticationToken(session, "", List.of(() -> "ROLE_USER"));
    }

    private static LocalToolExampleSummary summary(boolean installed) {
        MysqlLocalExampleCatalog.LocalExample example = new MysqlLocalExampleCatalog(new ObjectMapper()).find("echo").orElseThrow();
        return new LocalToolExampleSummary(example.key(), example.definition().id(), example.definition().name(),
                example.definition().description(), new ObjectMapper().createObjectNode(), example.sampleInput(), installed, true);
    }
}
