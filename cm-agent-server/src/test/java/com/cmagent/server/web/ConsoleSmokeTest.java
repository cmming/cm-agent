package com.cmagent.server.web;

import com.cmagent.server.CmAgentServerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("production")
@TestPropertySource(properties = "cm-agent.security.jwt-secret=cm-agent-console-smoke-jwt-secret-with-32-bytes")
class ConsoleSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void serveConsoleIndex() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CM Agent 控制台")));
    }

    @Test
    void consoleLoginUsesUserEnteredCredentials() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("id=\"loginUsername\""),
                        containsString("id=\"loginPassword\""),
                        containsString("type=\"password\""),
                        not(containsString("value=\"well-known-default-password\""))
                )));

        mockMvc.perform(get("/assets/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("$(\"loginUsername\")"),
                        containsString("$(\"loginPassword\")"),
                        not(containsString("well-known-default-password"))
                )));
    }

    @Test
    void swaggerUiIsNotPublicInProductionProfile() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isUnauthorized());
    }
}
