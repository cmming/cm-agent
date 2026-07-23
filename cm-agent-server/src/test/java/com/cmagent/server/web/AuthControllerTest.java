package com.cmagent.server.web;

import com.cmagent.core.audit.AuditEvent;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.BootstrapAdminProperties;
import com.cmagent.server.store.InMemoryPlatformStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "cm-agent.security.bootstrap-admin-enabled=true",
        "cm-agent.security.bootstrap-admin-password=admin-test-password-only",
        "cm-agent.security.bootstrap-admin-display-name=系统管理员"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AuthControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TEST_PASSWORD = "admin-test-password-only";
    private static final String[] EXPECTED_PERMISSIONS = {
            "agent:run",
            "agent:read",
            "agent:write",
            "tool:read",
            "tool:grant",
            "tool:debug",
            "tool:mcp:invoke",
            "audit:read",
            "apikey:write"
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryPlatformStore store;

    @Test
    void loginAndReadCurrentUser() throws Exception {
        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("系统管理员"))
                .andExpect(jsonPath("$.permissions[0]").value(EXPECTED_PERMISSIONS[0]))
                .andExpect(jsonPath("$.permissions[1]").value(EXPECTED_PERMISSIONS[1]))
                .andExpect(jsonPath("$.permissions[2]").value(EXPECTED_PERMISSIONS[2]))
                .andExpect(jsonPath("$.permissions[3]").value(EXPECTED_PERMISSIONS[3]))
                .andExpect(jsonPath("$.permissions[4]").value(EXPECTED_PERMISSIONS[4]))
                .andExpect(jsonPath("$.permissions[5]").value(EXPECTED_PERMISSIONS[5]))
                .andExpect(jsonPath("$.permissions[6]").value(EXPECTED_PERMISSIONS[6]))
                .andExpect(jsonPath("$.permissions[7]").value(EXPECTED_PERMISSIONS[7]))
                .andExpect(jsonPath("$.permissions[8]").value(EXPECTED_PERMISSIONS[8]))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accessToken = com.jayway.jsonpath.JsonPath.read(loginResponse, "$.accessToken");

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principalId").value("admin"))
                .andExpect(jsonPath("$.displayName").value("系统管理员"))
                .andExpect(jsonPath("$.permissions[0]").value(EXPECTED_PERMISSIONS[0]))
                .andExpect(jsonPath("$.permissions[1]").value(EXPECTED_PERMISSIONS[1]))
                .andExpect(jsonPath("$.permissions[2]").value(EXPECTED_PERMISSIONS[2]))
                .andExpect(jsonPath("$.permissions[3]").value(EXPECTED_PERMISSIONS[3]))
                .andExpect(jsonPath("$.permissions[4]").value(EXPECTED_PERMISSIONS[4]))
                .andExpect(jsonPath("$.permissions[5]").value(EXPECTED_PERMISSIONS[5]))
                .andExpect(jsonPath("$.permissions[6]").value(EXPECTED_PERMISSIONS[6]))
                .andExpect(jsonPath("$.permissions[7]").value(EXPECTED_PERMISSIONS[7]))
                .andExpect(jsonPath("$.permissions[8]").value(EXPECTED_PERMISSIONS[8]))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void loginRejectsInvalidCredentials() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRejectsBootstrapAdminWhenDisabledByDefault() throws Exception {
        BootstrapAdminProperties properties = new BootstrapAdminProperties(new MockEnvironment());
        MockMvc disabledBootstrapMockMvc = MockMvcBuilders.standaloneSetup(new AuthController(
                null,
                properties,
                new AuditAppender(new InMemoryPlatformStore())
        )).build();

        disabledBootstrapMockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"well-known-default-password"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRejectsFormerHardcodedPasswordWhenConfiguredPasswordDiffers() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"well-known-default-password"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWritesAuditEventsForSuccessAndFailure() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(TEST_PASSWORD)))
                .andExpect(status().isOk());

        List<AuditEvent> events = store.listAuditEvents(TENANT_ID);
        assertThat(events).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("LOGIN");
            assertThat(event.principalId()).isEqualTo("admin");
            assertThat(event.status()).isEqualTo("FAILED");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("LOGIN");
            assertThat(event.principalId()).isEqualTo("admin");
            assertThat(event.status()).isEqualTo("SUCCEEDED");
        });
    }

    @Test
    void meRejectsInvalidToken() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meRejectsTamperedToken() throws Exception {
        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accessToken = com.jayway.jsonpath.JsonPath.read(loginResponse, "$.accessToken");
        int signatureStart = accessToken.lastIndexOf('.') + 1;
        String tamperedToken = accessToken.substring(0, signatureStart)
                + (accessToken.charAt(signatureStart) == 'A' ? 'B' : 'A')
                + accessToken.substring(signatureStart + 1);

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginIgnoresMalformedAuthorizationHeader() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("Authorization", "Bearer bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("系统管理员"));
    }

    private String loginBody(String password) {
        return """
                {"username":"admin","password":"%s"}
                """.formatted(password);
    }
}
