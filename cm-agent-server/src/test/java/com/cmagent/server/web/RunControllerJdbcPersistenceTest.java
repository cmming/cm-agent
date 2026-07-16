package com.cmagent.server.web;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolCallRecord;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.persistence.JdbcModelConfigRepository;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
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
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @MockBean
    private AuditAppender auditAppender;

    @Autowired
    private com.cmagent.core.repository.RunRepository runRepository;

    @Autowired
    private AgentDefinitionRepository agentRepository;

    @Autowired
    private ModelConfigRepository modelConfigRepository;

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
    void jdbcProfileProvidesModelConfigRepository() {
        assertThat(modelConfigRepository).isInstanceOf(JdbcModelConfigRepository.class);
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

        String runResponse = mockMvc.perform(post("/api/agents/{agentId}/runs", agentId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input":"password=run-secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String runId = JsonPath.read(runResponse, "$.runId");

        mockMvc.perform(get("/api/agents/{agentId}/runs/{runId}", agentId, runId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.id").value(runId))
                .andExpect(jsonPath("$.run.input").value("password=<已脱敏>"));

        assertThat(lastRequest.get()).isNotNull();
        assertThat(lastRequest.get().tools()).extracting(ToolDefinition::name).containsExactly("echo");
    }

    @Test
    void persistsRedactedMappedToolCallsAndSkipsUnknownTools() throws Exception {
        String token = jwtService.createToken(TENANT_ID, "admin", "系统管理员", List.of(
                "agent:run", "agent:read", "agent:write", "tool:read", "tool:grant", "audit:read"
        ));
        String agentId = createAgent(token);
        String toolId = createTool(token);
        mockMvc.perform(post("/api/tools/{toolId}/grants", toolId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"%s\"}".formatted(agentId)))
                .andExpect(status().isOk());

        UUID authorizedToolId = UUID.fromString(toolId);
        doAnswer(invocation -> {
            AgentRunRequest request = invocation.getArgument(0);
            Instant now = Instant.now();
            return new AgentRunResult(
                    UUID.randomUUID(), RunStatus.SUCCEEDED, "output=call-output",
                    List.of(
                            new ToolCallRecord(
                                    authorizedToolId, "echo", "password=call-secret", "apiKey=call-key",
                                    RunStatus.SUCCEEDED, Duration.ofMillis(1234), true, "Bearer call-token"
                            ),
                            new ToolCallRecord(
                                    UUID.randomUUID(), "foreign", "foreign-input", "foreign-output",
                                    RunStatus.SUCCEEDED, Duration.ofMillis(5), true, "foreign-error"
                            )
                    ),
                    now, now, ""
            );
        }).when(agentRuntime).run(any());

        String runResponse = mockMvc.perform(post("/api/agents/{agentId}/runs", agentId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"hello\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String runId = JsonPath.read(runResponse, "$.runId");

        mockMvc.perform(get("/api/agents/{agentId}/runs/{runId}", agentId, runId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolCalls.length()").value(1))
                .andExpect(jsonPath("$.toolCalls[0].toolId").value(toolId))
                .andExpect(jsonPath("$.toolCalls[0].toolName").value("echo"))
                .andExpect(jsonPath("$.toolCalls[0].durationMillis").value(1234))
                .andExpect(jsonPath("$.toolCalls[0].inputSummary").value("password=<已脱敏>"))
                .andExpect(jsonPath("$.toolCalls[0].outputSummary").value("apiKey=<已脱敏>"))
                .andExpect(jsonPath("$.toolCalls[0].errorMessage").value("Bearer <已脱敏>"));
    }

    @Test
    void auditFailureRollsBackRunningRunAndSkipsRuntime() throws Exception {
        String token = jwtService.createToken(TENANT_ID, "admin", "系统管理员", List.of(
                "agent:run", "agent:read", "agent:write", "tool:read", "tool:grant", "audit:read"
        ));
        String agentResponse = mockMvc.perform(post("/api/agents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"审计失败助手","systemPrompt":"你是助手","modelName":"qwen-max"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String agentId = JsonPath.read(agentResponse, "$.id");

        doThrow(new AuditPersistenceException("审计写入失败", new IllegalStateException("database unavailable")))
                .when(auditAppender)
                .append(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        mockMvc.perform(post("/api/agents/{agentId}/runs", agentId)
                .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));

        verify(agentRuntime, never()).run(any());
        assertThat(runRepository.listByTenantAndAgent(
                TENANT_ID,
                UUID.fromString(agentId),
                new com.cmagent.core.domain.RunPageRequest(20, null, null)
        )).isEmpty();
    }

    @Test
    void completionAuditFailureClosesRolledBackRunAsFailedWithoutFailureAudit() throws Exception {
        String token = jwtService.createToken(TENANT_ID, "admin", "系统管理员", List.of(
                "agent:run", "agent:read", "agent:write", "tool:read", "tool:grant", "audit:read"
        ));
        String agentId = createAgent(token);
        AtomicInteger runAuditCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (runAuditCalls.incrementAndGet() > 1) {
                throw new AuditPersistenceException("审计写入失败", new IllegalStateException("database unavailable"));
            }
            return null;
        }).when(auditAppender).append(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        mockMvc.perform(post("/api/agents/{agentId}/runs", agentId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));

        verify(agentRuntime).run(any());
        assertThat(runRepository.listByTenantAndAgent(
                TENANT_ID,
                UUID.fromString(agentId),
                new com.cmagent.core.domain.RunPageRequest(20, null, null)
        )).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.output()).isEmpty();
            assertThat(run.errorMessage()).isEqualTo("Agent 运行失败");
        });
        assertThat(runAuditCalls).hasValue(2);
    }

    @Test
    void runtimeAuditFailureClosesRunAsFailedBeforeReturningOriginalAuditError() throws Exception {
        String token = jwtService.createToken(TENANT_ID, "admin", "系统管理员", List.of(
                "agent:run", "agent:read", "agent:write", "tool:read", "tool:grant", "audit:read"
        ));
        String agentId = createAgent(token);
        AtomicInteger runAuditCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (runAuditCalls.incrementAndGet() > 1) {
                throw new AuditPersistenceException("runtime 审计失败", new IllegalStateException("database unavailable"));
            }
            return null;
        }).when(auditAppender).append(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        doThrow(new AuditPersistenceException("runtime 审计失败", new IllegalStateException("runtime audit unavailable")))
                .when(agentRuntime).run(any());

        mockMvc.perform(post("/api/agents/{agentId}/runs", agentId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));

        verify(agentRuntime).run(any());
        assertThat(runRepository.listByTenantAndAgent(
                TENANT_ID,
                UUID.fromString(agentId),
                new com.cmagent.core.domain.RunPageRequest(20, null, null)
        )).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.output()).isEmpty();
            assertThat(run.errorMessage()).isEqualTo("Agent 运行失败");
        });
        assertThat(runAuditCalls).hasValue(1);
    }

    @Test
    void managementAuditFailureRollsBackJdbcAgentWrite() throws Exception {
        String token = jwtService.createToken(TENANT_ID, "admin", "系统管理员", List.of(
                "agent:read", "agent:write", "audit:read"
        ));
        doThrow(new AuditPersistenceException("审计写入失败", new IllegalStateException("database unavailable")))
                .when(auditAppender)
                .append(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        mockMvc.perform(post("/api/agents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"事务助手\",\"systemPrompt\":\"系统提示\",\"modelName\":\"qwen-max\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));

        assertThat(agentRepository.listByTenant(TENANT_ID))
                .noneMatch(agent -> agent.name().equals("事务助手"));
    }

    private String createAgent(String token) throws Exception {
        String response = mockMvc.perform(post("/api/agents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"企业助手\",\"systemPrompt\":\"你是企业助手\",\"modelName\":\"qwen-max\"}"))
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
                        .content("{\"name\":\"echo\",\"description\":\"回显输入\",\"type\":\"LOCAL\",\"riskLevel\":\"LOW\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

}
