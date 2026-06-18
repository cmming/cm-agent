package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.store.InMemoryPlatformStore;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "cm-agent.security.allow-dev-jwt-fallback=true")
@Import(RunControllerTest.TestRuntimeConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class RunControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CapturingAgentRuntime agentRuntime;

    @BeforeEach
    void resetRuntime() {
        agentRuntime.reset();
    }

    @Test
    void createAgentGrantToolRunAndAudit() throws Exception {
        String accessToken = loginToken();
        String agentId = createAgent(accessToken);
        String toolId = createTool(accessToken);

        mockMvc.perform(post("/api/tools/{toolId}/grants", toolId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":"%s"}
                                """.formatted(agentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(true));

        mockMvc.perform(post("/api/tools/{toolId}/grants", toolId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":"%s"}
                                """.formatted(agentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(true));

        mockMvc.perform(post("/api/agents/{agentId}/runs", agentId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input":"你好"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.output").value("fake-runtime: 你好"));

        assertThat(agentRuntime.lastRequest()).isNotNull();
        assertThat(agentRuntime.lastRequest().tools()).hasSize(1);
        assertThat(agentRuntime.lastRequest().tools().getFirst().name()).isEqualTo("echo");

        mockMvc.perform(get("/api/audit-events")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("AGENT_RUN"))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"));
    }

    @Test
    void runWithoutToolGrantStillSucceeds() throws Exception {
        String accessToken = loginToken();
        String agentId = createAgent(accessToken);

        mockMvc.perform(post("/api/agents/{agentId}/runs", agentId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input":"你好"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.output").value("fake-runtime: 你好"));

        assertThat(agentRuntime.lastRequest()).isNotNull();
        assertThat(agentRuntime.lastRequest().tools()).isEmpty();
    }

    @Test
    void createAgentRejectsBlankFieldsWithoutPersisting() throws Exception {
        String accessToken = loginToken();
        assertCount(accessToken, "/api/agents", 0);

        mockMvc.perform(post("/api/agents")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":" ","systemPrompt":"你是企业助手","modelName":"qwen-max"}
                                """))
                .andExpect(status().isBadRequest());

        assertCount(accessToken, "/api/agents", 0);
    }

    @Test
    void createToolRejectsBlankFieldsWithoutPersisting() throws Exception {
        String accessToken = loginToken();
        assertCount(accessToken, "/api/tools", 0);

        mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"echo","description":" ","type":"LOCAL","riskLevel":"LOW"}
                                """))
                .andExpect(status().isBadRequest());

        assertCount(accessToken, "/api/tools", 0);
    }

    @Test
    void createAgentWithoutWritePermissionIsForbidden() throws Exception {
        String token = tokenWithPermissions("readonly", List.of("agent:read"));

        mockMvc.perform(post("/api/agents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {"name":"企业助手","systemPrompt":"你是企业助手","modelName":"qwen-max"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void listAgentsWithoutReadPermissionIsForbidden() throws Exception {
        String token = tokenWithPermissions("agent-list-lite", List.of("agent:write"));

        mockMvc.perform(get("/api/agents")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAgentWithoutReadPermissionIsForbidden() throws Exception {
        String fullToken = loginToken();
        String agentId = createAgent(fullToken);
        String limitedToken = tokenWithPermissions("agent-get-lite", List.of("agent:write"));

        mockMvc.perform(get("/api/agents/{id}", agentId)
                        .header("Authorization", bearer(limitedToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createAndGrantToolWithoutGrantPermissionIsForbidden() throws Exception {
        String createToken = loginToken();
        String agentId = createAgent(createToken);
        String toolId = createTool(createToken);

        String token = tokenWithPermissions("limited-tool", List.of("agent:write"));

        mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"calc","description":"计算","type":"LOCAL","riskLevel":"LOW"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/tools/{toolId}/grants", toolId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":"%s"}
                                """.formatted(agentId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listToolsWithoutReadPermissionIsForbidden() throws Exception {
        String token = tokenWithPermissions("tool-list-lite", List.of("agent:write", "tool:grant"));

        mockMvc.perform(get("/api/tools")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void runWithoutRunPermissionIsForbidden() throws Exception {
        String setupToken = loginToken();
        String agentId = createAgent(setupToken);
        String toolId = createTool(setupToken);

        mockMvc.perform(post("/api/tools/{toolId}/grants", toolId)
                        .header("Authorization", bearer(setupToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":"%s"}
                                """.formatted(agentId)))
                .andExpect(status().isOk());

        String token = tokenWithPermissions("limited-run", List.of("agent:read"));

        mockMvc.perform(post("/api/agents/{agentId}/runs", agentId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {"input":"你好"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void createAgentWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"企业助手","systemPrompt":"你是企业助手","modelName":"qwen-max"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listAuditEventsWithoutReadPermissionIsForbidden() throws Exception {
        String token = tokenWithPermissions("audit-lite", List.of("agent:run"));

        mockMvc.perform(get("/api/audit-events")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void runFailureIsAuditedAndReturnsServerError() throws Exception {
        String accessToken = loginToken();
        agentRuntime.failNextRun();
        String agentId = createAgent(accessToken);

        mockMvc.perform(post("/api/agents/{agentId}/runs", agentId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input":"你好"}
                                """))
                .andExpect(status().is5xxServerError());

        mockMvc.perform(get("/api/audit-events")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("AGENT_RUN"))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].message").value("runtime boom"));
    }

    @Test
    void grantIsIdempotent() throws Exception {
        String accessToken = loginToken();
        String agentId = createAgent(accessToken);
        String toolId = createTool(accessToken);

        mockMvc.perform(post("/api/tools/{toolId}/grants", toolId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":"%s"}
                                """.formatted(agentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(true));

        mockMvc.perform(post("/api/tools/{toolId}/grants", toolId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":"%s"}
                                """.formatted(agentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(true));

        mockMvc.perform(post("/api/agents/{agentId}/runs", agentId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input":"你好"}
                                """))
                .andExpect(status().isOk());

        assertThat(agentRuntime.lastRequest()).isNotNull();
        assertThat(agentRuntime.lastRequest().tools()).extracting(ToolDefinition::name).containsExactly("echo");
    }

    private String createAgent(String token) throws Exception {
        String response = mockMvc.perform(post("/api/agents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"企业助手","systemPrompt":"你是企业助手","modelName":"qwen-max"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String createTool(String token) throws Exception {
        String response = mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"echo","description":"回显输入","type":"LOCAL","riskLevel":"LOW"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private void assertCount(String token, String path, int expected) throws Exception {
        mockMvc.perform(get(path)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(expected));
    }

    private String loginToken() throws Exception {
        return tokenWithPermissions("admin", List.of(
                "agent:run",
                "agent:read",
                "agent:write",
                "tool:read",
                "tool:grant",
                "audit:read",
                "apikey:write"
        ));
    }

    private String tokenWithPermissions(String principalId, List<String> permissions) {
        return jwtService.createToken(TENANT_ID, principalId, "系统管理员", permissions);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @TestConfiguration
    static class TestRuntimeConfig {

        @Bean
        @Primary
        CapturingAgentRuntime agentRuntime() {
            return new CapturingAgentRuntime();
        }
    }

    static class CapturingAgentRuntime implements AgentRuntime {
        private final AtomicReference<AgentRunRequest> lastRequest = new AtomicReference<>();
        private final AtomicBoolean failNextRun = new AtomicBoolean(false);

        @Override
        public AgentRunResult run(AgentRunRequest request) {
            lastRequest.set(request);
            if (failNextRun.getAndSet(false)) {
                throw new IllegalStateException("runtime boom");
            }
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
        }

        void failNextRun() {
            failNextRun.set(true);
        }

        AgentRunRequest lastRequest() {
            return lastRequest.get();
        }

        void reset() {
            lastRequest.set(null);
            failNextRun.set(false);
        }
    }
}
