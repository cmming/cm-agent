package com.cmagent.server.web;

import com.cmagent.server.CmAgentServerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class)
@AutoConfigureMockMvc
class AuthControllerTest {

    private static final String[] EXPECTED_PERMISSIONS = {
            "agent:run",
            "agent:read",
            "agent:write",
            "tool:read",
            "tool:grant",
            "audit:read",
            "apikey:write"
    };

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginAndReadCurrentUser() throws Exception {
        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"admin123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("系统管理员"))
                .andExpect(jsonPath("$.permissions[0]").value(EXPECTED_PERMISSIONS[0]))
                .andExpect(jsonPath("$.permissions[1]").value(EXPECTED_PERMISSIONS[1]))
                .andExpect(jsonPath("$.permissions[2]").value(EXPECTED_PERMISSIONS[2]))
                .andExpect(jsonPath("$.permissions[3]").value(EXPECTED_PERMISSIONS[3]))
                .andExpect(jsonPath("$.permissions[4]").value(EXPECTED_PERMISSIONS[4]))
                .andExpect(jsonPath("$.permissions[5]").value(EXPECTED_PERMISSIONS[5]))
                .andExpect(jsonPath("$.permissions[6]").value(EXPECTED_PERMISSIONS[6]))
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
    void meRejectsInvalidToken() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meRejectsTamperedToken() throws Exception {
        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"admin123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accessToken = com.jayway.jsonpath.JsonPath.read(loginResponse, "$.accessToken");
        String tamperedToken = accessToken.substring(0, accessToken.length() - 1)
                + (accessToken.endsWith("a") ? "b" : "a");

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginIgnoresMalformedAuthorizationHeader() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("Authorization", "Bearer bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"admin123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("系统管理员"));
    }
}
