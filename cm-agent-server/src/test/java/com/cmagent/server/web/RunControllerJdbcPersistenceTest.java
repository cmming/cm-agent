package com.cmagent.server.web;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RunControllerJdbcPersistenceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void jdbcProperties(DynamicPropertyRegistry registry) {
        registry.add("cm-agent.fake-runtime-enabled", () -> "false");
        registry.add("cm-agent.persistence.mode", () -> "jdbc");
        registry.add("cm-agent.persistence.jdbc.url", postgres::getJdbcUrl);
        registry.add("cm-agent.persistence.jdbc.username", postgres::getUsername);
        registry.add("cm-agent.persistence.jdbc.password", postgres::getPassword);
        registry.add("cm-agent.persistence.jdbc.driver-class-name", postgres::getDriverClassName);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private AgentRuntime agentRuntime;

    private final AtomicReference<AgentRunRequest> lastRequest = new AtomicReference<>();

    @BeforeEach
    void arrangeRuntime() {
        lastRequest.set(null);
        when(agentRuntime.run(any())).thenAnswer(invocation -> {
            AgentRunRequest request = invocation.getArgument(0);
            lastRequest.set(request);
            Instant now = Instant.now();
            return new AgentRunResult(
                    UUID.randomUUID(),
                    RunStatus.SUCCEEDED,
                    "fake-runtime: " + request.input(),
                    List.of(),
                    now,
                    now,
                    ""
            );
        });
    }

    @Test
    void createToolGrantAndRunLoadsAuthorizedToolFromJdbc() throws Exception {
        String token = jwtService.createToken(TENANT_ID, "admin", "系统管理员", List.of(
                "agent:run",
                "agent:read",
                "agent:write",
                "tool:read",
                "tool:grant",
                "audit:read",
                "apikey:write"
        ));

        String agentResponse = mockMvc.perform(post("/api/agents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"企业助手","systemPrompt":"你是企业助手","modelName":"qwen-max"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String agentId = JsonPath.read(agentResponse, "$.id");

        String toolResponse = mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"echo","description":"回显输入","type":"LOCAL","riskLevel":"LOW"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String toolId = JsonPath.read(toolResponse, "$.id");

        mockMvc.perform(post("/api/tools/{toolId}/grants", toolId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":"%s"}
                                """.formatted(agentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(true));

        mockMvc.perform(post("/api/agents/{agentId}/runs", agentId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input":"你好"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        assertThat(lastRequest.get()).isNotNull();
        assertThat(lastRequest.get().tools()).extracting(ToolDefinition::name).containsExactly("echo");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

}
