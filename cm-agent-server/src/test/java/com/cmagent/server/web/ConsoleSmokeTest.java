package com.cmagent.server.web;

import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtAuthenticationFilter;
import com.cmagent.server.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("production")
@TestPropertySource(properties = "cm-agent.security.jwt-secret=cm-agent-console-smoke-jwt-secret-with-32-bytes")
@Testcontainers
class ConsoleSmokeTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void jdbcProperties(DynamicPropertyRegistry registry) {
        registry.add("cm-agent.persistence.mode", () -> "jdbc");
        registry.add("cm-agent.persistence.jdbc.url", postgres::getJdbcUrl);
        registry.add("cm-agent.persistence.jdbc.username", postgres::getUsername);
        registry.add("cm-agent.persistence.jdbc.password", postgres::getPassword);
        registry.add("cm-agent.persistence.jdbc.driver-class-name", postgres::getDriverClassName);
    }

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

class SupabaseConsoleSmokeTest {

    @Test
    void swaggerUiIsNotPublicInSupabaseProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("supabase");
        SecurityConfig securityConfig = new SecurityConfig(mock(JwtAuthenticationFilter.class), environment, true);

        Boolean publicApiDocsAllowed = ReflectionTestUtils.invokeMethod(securityConfig, "isPublicApiDocsAllowed");

        assertThat(publicApiDocsAllowed).isFalse();
    }
}
