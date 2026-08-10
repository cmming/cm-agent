package com.cmagent.server.web;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.ManagementCommandService;
import com.cmagent.server.service.ToolQueryService;
import com.cmagent.server.service.ToolSummary;
import com.cmagent.server.service.ToolDebugService;
import com.cmagent.server.service.McpPublicationService;
import com.cmagent.server.store.InMemoryPlatformStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.validation.Validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ToolControllerTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private InMemoryPlatformStore store;

    @Autowired
    private Validator validator;

    @SpyBean
    private AuditAppender auditAppender;

    @SpyBean
    private ManagementCommandService managementCommandService;

    @Test
    /**
     * 验证或支持 {@code localCreateKeepsExistingFieldsAndHasNoHttpConfiguration} 所描述的测试场景。
     */
    void localCreateKeepsExistingFieldsAndHasNoHttpConfiguration() throws Exception {
        String token = token(TENANT_A, "admin");
        String toolId = createLocal(token, "echo");

        mockMvc.perform(get("/api/tools")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(toolId))
                .andExpect(jsonPath("$[0].tenantId").value(TENANT_A.toString()))
                .andExpect(jsonPath("$[0].name").value("echo"))
                .andExpect(jsonPath("$[0].description").value("回显"))
                .andExpect(jsonPath("$[0].type").value("LOCAL"))
                .andExpect(jsonPath("$[0].inputSchema").value("{\"type\":\"object\"}"))
                .andExpect(jsonPath("$[0].riskLevel").value("LOW"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].endpoint").value(""))
                .andExpect(jsonPath("$[0].createdBy").value("admin"))
                .andExpect(jsonPath("$[0].updatedBy").value("admin"))
                .andExpect(jsonPath("$[0].httpConfig").doesNotExist())
                .andExpect(jsonPath("$[0].mcpPublished").value(false))
                .andExpect(jsonPath("$[0].runtimeReady").value(false));
    }

    @Test
    void 新版Http参数定义自动生成Schema并返回参数树() throws Exception {
        String token = token(TENANT_A, "admin");

        String response = mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"array-body-tool",
                                  "description":"根数组请求体",
                                  "type":"HTTP",
                                  "riskLevel":"LOW",
                                  "httpConfig":{
                                    "method":"POST",
                                    "urlTemplate":"https://api.example.test/orders/{orderId}",
                                    "parameters":[
                                      {"id":"orderId","name":"orderId","dataType":"STRING","requestLocation":"PATH","required":true},
                                      {"id":"payload","name":"payload","dataType":"ARRAY","requestLocation":"BODY_ROOT","required":true,"minItems":1},
                                      {"id":"payloadItem","parentId":"payload","dataType":"OBJECT"},
                                      {"id":"p1","parentId":"payloadItem","name":"p1","dataType":"STRING","required":true,"exampleValue":"v1"}
                                    ],
                                    "secretHeaders":{},
                                    "timeoutMillis":3000
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpConfig.inputSchema").doesNotExist())
                .andExpect(jsonPath("$.httpConfig.parameters.length()").value(4))
                .andExpect(jsonPath("$.httpConfig.parameters[1].requestLocation").value("BODY_ROOT"))
                .andExpect(jsonPath("$.httpConfig.parameters[2].name").value(""))
                .andReturn().getResponse().getContentAsString();

        UUID toolId = UUID.fromString(JsonPath.read(response, "$.id"));
        HttpToolConfig config = store.findHttpToolConfig(TENANT_A, toolId).orElseThrow();
        assertThat(config.parameters()).hasSize(4);
        assertThat(JsonPath.<String>read(response, "$.inputSchema"))
                .contains("\"payload\"", "\"items\"", "\"p1\"");
    }

    @Test
    void 无参数Http工具允许提交空数组并生成空对象Schema() throws Exception {
        String token = token(TENANT_A, "admin");

        String response = mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"tool-list",
                                  "description":"获取工具列表",
                                  "type":"HTTP",
                                  "riskLevel":"LOW",
                                  "httpConfig":{
                                    "method":"GET",
                                    "urlTemplate":"https://api.example.test/tools",
                                    "parameters":[],
                                    "secretHeaders":{},
                                    "timeoutMillis":1000
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpConfig.parameters").isEmpty())
                .andReturn().getResponse().getContentAsString();

        UUID toolId = UUID.fromString(JsonPath.read(response, "$.id"));
        HttpToolConfig config = store.findHttpToolConfig(TENANT_A, toolId).orElseThrow();
        assertThat(config.parameters()).isEmpty();
        assertThat(JsonPath.<String>read(response, "$.inputSchema"))
                .contains("\"properties\":{}", "\"additionalProperties\":false");
    }

    @Test
    void 旧版Schema不能替代Parameters() throws Exception {
        String token = token(TENANT_A, "admin");

        mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"mixed-http-config",
                                  "description":"混用配置",
                                  "type":"HTTP",
                                  "riskLevel":"LOW",
                                  "httpConfig":{
                                    "method":"GET",
                                    "urlTemplate":"https://api.example.test/items/{id}",
                                    "inputSchema":{"type":"object"},
                                    "timeoutMillis":1000
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    /**
     * 验证或支持 {@code httpCreateRequiresHttpConfigurationAndLocalRejectsIt} 所描述的测试场景。
     */
    void httpCreateRequiresHttpConfigurationAndLocalRejectsIt() throws Exception {
        String token = token(TENANT_A, "admin");

        mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"missing-http\",\"description\":\"缺少配置\",\"type\":\"HTTP\",\"riskLevel\":\"LOW\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"invalid-local","description":"本地工具","type":"LOCAL","riskLevel":"LOW",
                                 "httpConfig":{"method":"POST","urlTemplate":"https://api.example.test","parameters":[],"timeoutMillis":1000}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    /**
     * 验证或支持 {@code httpCreateRejectsNullParameterMapping} 所描述的测试场景。
     */
    void httpCreateRejectsNullParameterDefinition() throws Exception {
        String token = token(TENANT_A, "admin");

        mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"null-parameter","description":"空参数节点","type":"HTTP","riskLevel":"LOW",
                                 "httpConfig":{"method":"POST","urlTemplate":"https://api.example.test",
                                 "parameters":[null],"timeoutMillis":1000}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    /**
     * 验证或支持 {@code httpCreateRejectsNullSecretHeaderValueAndBlankKeyAsBadRequest} 所描述的测试场景。
     */
    void httpCreateRejectsNullSecretHeaderValueAndBlankKeyAsBadRequest() throws Exception {
        String token = token(TENANT_A, "admin");

        mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"null-secret-value","description":"空值","type":"HTTP","riskLevel":"LOW",
                                 "httpConfig":{"method":"POST","urlTemplate":"https://api.example.test",
                                 "parameters":[{"id":"payload","name":"payload","dataType":"STRING","requestLocation":"BODY"}],
                                 "secretHeaders":{"Authorization":null},"timeoutMillis":1000}}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"blank-secret-key","description":"空键","type":"HTTP","riskLevel":"LOW",
                                 "httpConfig":{"method":"POST","urlTemplate":"https://api.example.test",
                                 "parameters":[{"id":"payload","name":"payload","dataType":"STRING","requestLocation":"BODY"}],
                                 "secretHeaders":{"":"secret/integration/token"},"timeoutMillis":1000}}
                                """))
                .andExpect(status().isBadRequest());

        HashMap<String, String> secretHeadersWithNullKey = new HashMap<>();
        secretHeadersWithNullKey.put(null, "secret/integration/token");
        ToolController.HttpConfigRequest request = new ToolController.HttpConfigRequest(
                com.cmagent.core.domain.HttpToolMethod.POST,
                "https://api.example.test",
                List.of(parameterRequest()),
                secretHeadersWithNullKey,
                1000L
        );
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    /**
     * 验证或支持 {@code duplicateNameIsRejectedWithinTenantButAllowedAcrossTenants} 所描述的测试场景。
     */
    void duplicateNameIsRejectedWithinTenantButAllowedAcrossTenants() throws Exception {
        String tenantAToken = token(TENANT_A, "admin-a");
        String tenantBToken = token(TENANT_B, "admin-b");
        createLocal(tenantAToken, "shared-name");

        mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(tenantAToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"shared-name\",\"description\":\"重复\",\"type\":\"LOCAL\",\"riskLevel\":\"LOW\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(tenantBToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"shared-name\",\"description\":\"另一个租户\",\"type\":\"LOCAL\",\"riskLevel\":\"LOW\"}"))
                .andExpect(status().isOk());
    }

    @Test
    /**
     * 验证或支持 {@code auditFailureLeavesNoHttpToolConfigurationOrPublication} 所描述的测试场景。
     */
    void auditFailureLeavesNoHttpToolConfigurationOrPublication() throws Exception {
        String token = token(TENANT_A, "admin");
        doThrow(new AuditPersistenceException("审计写入失败", new IllegalStateException("audit unavailable")))
                .when(auditAppender).appendAll(any());

        mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(httpRequest("audit-failure", true)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));

        assertThat(store.listTools(TENANT_A)).isEmpty();
        assertThat(store.listEnabledMcpToolPublications(TENANT_A)).isEmpty();
    }

    @Test
    /**
     * 验证或支持 {@code debugPermissionDeniedWritesAuditBeforeAnyToolExecution} 所描述的测试场景。
     */
    void debugPermissionDeniedWritesAuditBeforeAnyToolExecution() throws Exception {
        String token = token(TENANT_A, "reader");
        String toolId = createLocal(token, "debug-target");

        mockMvc.perform(post("/api/tools/{id}/debug", toolId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{}}"))
                .andExpect(status().isForbidden());

        assertThat(store.listAuditEvents(TENANT_A)).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("ACCESS_DENIED");
            assertThat(event.resourceId()).isEqualTo(toolId);
            assertThat(event.message()).contains("tool:debug");
        });
    }

    @Test
    /**
     * 验证或支持 {@code publicationEndpointsUseManagementPermissionAndDeleteIsIdempotent} 所描述的测试场景。
     */
    void publicationEndpointsUseManagementPermissionAndDeleteIsIdempotent() throws Exception {
        String token = token(TENANT_A, "admin");
        String toolId = createLocal(token, "local_publish_target");

        mockMvc.perform(put("/api/tools/{id}/mcp-publication", toolId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/tools/{id}/mcp-publication", toolId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
    }

    @Test
    /**
     * 验证或支持 {@code httpUpdateUsesGrantPermissionAndReturnsUpdatedSummary} 所描述的测试场景。
     */
    void httpUpdateUsesGrantPermissionAndReturnsUpdatedSummary() throws Exception {
        String managerToken = token(TENANT_A, "manager");
        String toolId = createHttp(managerToken, "orders-before-update");

        mockMvc.perform(put("/api/tools/{id}", toolId)
                        .header("Authorization", bearer(managerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(httpUpdateRequest("orders-after-update")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(toolId))
                .andExpect(jsonPath("$.name").value("orders-after-update"))
                .andExpect(jsonPath("$.description").value("更新后的订单查询"))
                .andExpect(jsonPath("$.type").value("HTTP"))
                .andExpect(jsonPath("$.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.endpoint").value("https://api.example.test/updated/{id}"))
                .andExpect(jsonPath("$.inputSchema").value(
                        "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
                                + "\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"],"
                                + "\"additionalProperties\":false}"))
                .andExpect(jsonPath("$.httpConfig.method").value("GET"))
                .andExpect(jsonPath("$.httpConfig.urlTemplate").value("https://api.example.test/updated/{id}"))
                .andExpect(jsonPath("$.httpConfig.secretHeaders.Authorization").value("secret/integration/updated-token"))
                .andExpect(jsonPath("$.httpConfig.timeoutMillis").value(2000))
                .andExpect(jsonPath("$.mcpPublished").value(true));
    }

    @Test
    /**
     * 验证或支持 {@code concurrentHttpUpdatesEachReturnTheirOwnCommittedSnapshot} 所描述的测试场景。
     */
    void concurrentHttpUpdatesEachReturnTheirOwnCommittedSnapshot() throws Exception {
        String managerToken = token(TENANT_A, "manager");
        String toolId = createHttp(managerToken, "orders-before-concurrent-update");
        UUID id = UUID.fromString(toolId);
        CountDownLatch firstCommandCompleted = new CountDownLatch(1);
        CountDownLatch releaseFirstResponse = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            ManagementCommandService.ToolUpdateSpec spec = invocation.getArgument(2);
            if ("orders-first-update".equals(spec.name())) {
                firstCommandCompleted.countDown();
                if (!releaseFirstResponse.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发测试未释放第一个更新响应");
                }
            }
            return result;
        }).when(managementCommandService).updateTool(any(), org.mockito.ArgumentMatchers.eq(id), any());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> mockMvc.perform(put("/api/tools/{id}", toolId)
                            .header("Authorization", bearer(managerToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(httpUpdateRequest("orders-first-update")))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
            assertThat(firstCommandCompleted.await(5, TimeUnit.SECONDS)).isTrue();

            String second = mockMvc.perform(put("/api/tools/{id}", toolId)
                            .header("Authorization", bearer(managerToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(httpUpdateRequest("orders-second-update")))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            releaseFirstResponse.countDown();
            String firstResponse = first.get(5, TimeUnit.SECONDS);

            assertThat(JsonPath.<String>read(firstResponse, "$.name")).isEqualTo("orders-first-update");
            assertThat(JsonPath.<String>read(second, "$.name")).isEqualTo("orders-second-update");
        } finally {
            releaseFirstResponse.countDown();
        }
        assertThat(store.findTool(TENANT_A, id)).get()
                .extracting(ToolDefinition::name)
                .isEqualTo("orders-second-update");
        assertThat(store.listAuditEvents(TENANT_A).stream()
                .filter(event -> "TOOL_UPDATE".equals(event.eventType()))
                .filter(event -> toolId.equals(event.resourceId())))
                .hasSize(2);
    }

    @Test
    /**
     * 验证或支持 {@code updateDeniedWithoutGrantPermissionWritesAccessDeniedAudit} 所描述的测试场景。
     */
    void updateDeniedWithoutGrantPermissionWritesAccessDeniedAudit() throws Exception {
        String managerToken = token(TENANT_A, "manager");
        String toolId = createHttp(managerToken, "update-denied");
        String readerToken = token(TENANT_A, "reader", List.of("tool:read"));

        mockMvc.perform(put("/api/tools/{id}", toolId)
                        .header("Authorization", bearer(readerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(httpUpdateRequest("update-denied-after")))
                .andExpect(status().isForbidden());

        assertThat(store.listAuditEvents(TENANT_A)).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("ACCESS_DENIED");
            assertThat(event.resourceId()).isEqualTo(toolId);
            assertThat(event.message()).contains("tool:grant");
        });
    }

    @Test
    /**
     * 验证或支持 {@code updateReturnsNotFoundForToolOutsideCurrentTenant} 所描述的测试场景。
     */
    void updateReturnsNotFoundForToolOutsideCurrentTenant() throws Exception {
        String tenantAToken = token(TENANT_A, "manager-a");
        String tenantBToken = token(TENANT_B, "manager-b");
        String toolId = createHttp(tenantAToken, "tenant-a-update-target");

        mockMvc.perform(put("/api/tools/{id}", toolId)
                        .header("Authorization", bearer(tenantBToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(httpUpdateRequest("tenant-b-update")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUNTIME_ERROR"));
    }

    @Test
    /**
     * 验证或支持 {@code updateRejectsDuplicateNameWithinTenant} 所描述的测试场景。
     */
    void updateRejectsDuplicateNameWithinTenant() throws Exception {
        String managerToken = token(TENANT_A, "manager");
        String firstToolId = createHttp(managerToken, "update-first");
        createHttp(managerToken, "update-second");

        mockMvc.perform(put("/api/tools/{id}", firstToolId)
                        .header("Authorization", bearer(managerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(httpUpdateRequest("update-second")))
                .andExpect(status().isConflict());
    }

    @Test
    /**
     * 验证或支持 {@code deleteUsesDeletePermissionAndReturnsNoContent} 所描述的测试场景。
     */
    void deleteUsesDeletePermissionAndReturnsNoContent() throws Exception {
        String managerToken = token(TENANT_A, "manager");
        String toolId = createLocal(managerToken, "delete-success");
        String deleteToken = token(TENANT_A, "deleter", List.of("tool:delete"));

        mockMvc.perform(delete("/api/tools/{id}", toolId)
                        .header("Authorization", bearer(deleteToken)))
                .andExpect(status().isNoContent());

        assertThat(store.findTool(TENANT_A, UUID.fromString(toolId))).isEmpty();
    }

    @Test
    /**
     * 验证或支持 {@code deleteDeniedWithoutDeletePermissionWritesAccessDeniedAudit} 所描述的测试场景。
     */
    void deleteDeniedWithoutDeletePermissionWritesAccessDeniedAudit() throws Exception {
        String managerToken = token(TENANT_A, "manager");
        String toolId = createLocal(managerToken, "delete-denied");
        String readerToken = token(TENANT_A, "reader", List.of("tool:read"));

        mockMvc.perform(delete("/api/tools/{id}", toolId)
                        .header("Authorization", bearer(readerToken)))
                .andExpect(status().isForbidden());

        assertThat(store.findTool(TENANT_A, UUID.fromString(toolId))).isPresent();
        assertThat(store.listAuditEvents(TENANT_A)).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("ACCESS_DENIED");
            assertThat(event.resourceId()).isEqualTo(toolId);
            assertThat(event.message()).contains("tool:delete");
        });
    }

    @Test
    /**
     * 验证或支持 {@code deleteRejectsToolStillReferencedByAgent} 所描述的测试场景。
     */
    void deleteRejectsToolStillReferencedByAgent() throws Exception {
        String managerToken = token(TENANT_A, "manager");
        String toolId = createLocal(managerToken, "delete-referenced");
        UUID agentId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        saveAgent(TENANT_A, agentId);
        grantTool(managerToken, toolId, agentId);
        String deleteToken = token(TENANT_A, "deleter", List.of("tool:delete"));

        mockMvc.perform(delete("/api/tools/{id}", toolId)
                        .header("Authorization", bearer(deleteToken)))
                .andExpect(status().isConflict());

        assertThat(store.findTool(TENANT_A, UUID.fromString(toolId))).isPresent();
    }

    @Test
    /**
     * 验证或支持 {@code deleteCannotReachToolFromAnotherTenant} 所描述的测试场景。
     */
    void deleteCannotReachToolFromAnotherTenant() throws Exception {
        String tenantAManagerToken = token(TENANT_A, "manager-a");
        String toolId = createLocal(tenantAManagerToken, "cross-tenant-delete");
        String tenantBDeleteToken = token(TENANT_B, "deleter-b", List.of("tool:delete"));

        mockMvc.perform(delete("/api/tools/{id}", toolId)
                        .header("Authorization", bearer(tenantBDeleteToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUNTIME_ERROR"));

        assertThat(store.findTool(TENANT_A, UUID.fromString(toolId))).isPresent();
    }

    @Test
    /**
     * 验证或支持 {@code revokeUsesGrantPermissionAndReturnsUpdatedAgent} 所描述的测试场景。
     */
    void revokeUsesGrantPermissionAndReturnsUpdatedAgent() throws Exception {
        String managerToken = token(TENANT_A, "manager");
        String toolId = createLocal(managerToken, "revoke-success");
        UUID agentId = UUID.fromString("30000000-0000-0000-0000-000000000002");
        saveAgent(TENANT_A, agentId);
        grantTool(managerToken, toolId, agentId);

        mockMvc.perform(delete("/api/tools/{toolId}/grants/{agentId}", toolId, agentId)
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(agentId.toString()))
                .andExpect(jsonPath("$.toolIds").isEmpty());

        assertThat(store.listGrants(TENANT_A, agentId, UUID.fromString(toolId))).isEmpty();
    }

    @Test
    /**
     * 验证或支持 {@code revokeDeniedWithoutGrantPermissionWritesAccessDeniedAudit} 所描述的测试场景。
     */
    void revokeDeniedWithoutGrantPermissionWritesAccessDeniedAudit() throws Exception {
        String managerToken = token(TENANT_A, "manager");
        String toolId = createLocal(managerToken, "revoke-denied");
        UUID agentId = UUID.fromString("30000000-0000-0000-0000-000000000003");
        saveAgent(TENANT_A, agentId);
        grantTool(managerToken, toolId, agentId);
        String readerToken = token(TENANT_A, "reader", List.of("tool:read"));

        mockMvc.perform(delete("/api/tools/{toolId}/grants/{agentId}", toolId, agentId)
                        .header("Authorization", bearer(readerToken)))
                .andExpect(status().isForbidden());

        assertThat(store.listAuditEvents(TENANT_A)).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("ACCESS_DENIED");
            assertThat(event.resourceId()).isEqualTo(toolId);
            assertThat(event.message()).contains("tool:grant");
        });
    }

    @Test
    /**
     * 验证或支持 {@code revokeReturnsNotFoundWhenAgentDoesNotExist} 所描述的测试场景。
     */
    void revokeReturnsNotFoundWhenAgentDoesNotExist() throws Exception {
        String managerToken = token(TENANT_A, "manager");
        String toolId = createLocal(managerToken, "revoke-missing-agent");
        UUID missingAgentId = UUID.fromString("30000000-0000-0000-0000-000000000004");

        mockMvc.perform(delete("/api/tools/{toolId}/grants/{agentId}", toolId, missingAgentId)
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUNTIME_ERROR"));
    }

    @Test
    /**
     * 验证或支持 {@code createReturnsTheToolCreatedByCommandInsteadOfAnotherSameNameSummary} 所描述的测试场景。
     */
    void createReturnsTheToolCreatedByCommandInsteadOfAnotherSameNameSummary() {
        PermissionEvaluator permissionEvaluator = mock(PermissionEvaluator.class);
        AuditAppender controllerAuditAppender = mock(AuditAppender.class);
        ManagementCommandService managementCommandService = mock(ManagementCommandService.class);
        ToolQueryService toolQueryService = mock(ToolQueryService.class);
        ToolDebugService toolDebugService = mock(ToolDebugService.class);
        McpPublicationService mcpPublicationService = mock(McpPublicationService.class);
        ToolDefinition created = localTool(UUID.fromString("20000000-0000-0000-0000-000000000001"), "same-name");
        when(permissionEvaluator.check(any(), anyString())).thenReturn(AuthorizationDecision.allow());
        when(managementCommandService.createTool(any(), anyString(), anyString(), any(), any(), isNull(), anyBoolean()))
                .thenReturn(created);
        when(toolQueryService.findByTenantAndId(TENANT_A, created.id()))
                .thenReturn(Optional.of(new ToolSummary(created, null, false, false)));
        org.springframework.security.core.Authentication authentication = mock(org.springframework.security.core.Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(new JwtService.JwtSession(TENANT_A, "admin", "管理员", List.of("tool:grant")));
        ToolController controller = new ToolController(
                permissionEvaluator, controllerAuditAppender, managementCommandService, new ObjectMapper(), toolQueryService,
                toolDebugService, mcpPublicationService
        );

        ToolController.ToolSummaryResponse response = controller.create(
                new ToolController.ToolCreateRequest("same-name", "创建结果", ToolType.LOCAL, ToolRiskLevel.LOW, false, null),
                authentication
        );

        assertThat(response.id()).isEqualTo(created.id());
        verify(toolQueryService).findByTenantAndId(TENANT_A, created.id());
        verify(toolQueryService, never()).listByTenant(TENANT_A);
    }

    /**
     * 验证或支持 {@code localTool} 所描述的测试场景。
     *
     * @param id 测试辅助方法使用的 id 参数
     * @param name 测试对象名称
     */
    private static ToolDefinition localTool(UUID id, String name) {
        return new ToolDefinition(
                id, TENANT_A, name, "", ToolType.LOCAL, "{\"type\":\"object\"}", ToolRiskLevel.LOW,
                true, "", "admin", "admin"
        );
    }

    /**
     * 验证或支持 {@code createLocal} 所描述的测试场景。
     *
     * @param token 测试辅助方法使用的 token 参数
     * @param name 测试对象名称
     */
    private String createLocal(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"回显","type":"LOCAL","riskLevel":"LOW"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    /**
     * 验证或支持 {@code createHttp} 所描述的测试场景。
     *
     * @param token 测试辅助方法使用的 token 参数
     * @param name 测试对象名称
     */
    private String createHttp(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(httpRequest(name, false)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    /**
     * 验证或支持 {@code saveAgent} 所描述的测试场景。
     *
     * @param tenantId 测试租户标识
     * @param agentId 测试 Agent 标识
     */
    private void saveAgent(UUID tenantId, UUID agentId) {
        store.saveAgent(new AgentDefinition(
                agentId,
                tenantId,
                "测试 Agent",
                "用于工具管理接口测试",
                "你是测试 Agent",
                null,
                "test-model",
                0.2,
                8,
                true,
                List.of(),
                "admin",
                "admin"
        ));
    }

    /**
     * 验证或支持 {@code grantTool} 所描述的测试场景。
     *
     * @param token 测试辅助方法使用的 token 参数
     * @param toolId 测试工具标识
     * @param agentId 测试 Agent 标识
     */
    private void grantTool(String token, String toolId, UUID agentId) throws Exception {
        mockMvc.perform(post("/api/tools/{id}/grants", toolId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"%s\"}".formatted(agentId)))
                .andExpect(status().isOk());
    }

    /**
     * 验证或支持 {@code httpRequest} 所描述的测试场景。
     *
     * @param name 测试对象名称
     * @param mcpPublished 测试辅助方法使用的 mcpPublished 参数
     */
    private String httpRequest(String name, boolean mcpPublished) {
        return """
                {
                  "name":"%s",
                  "description":"订单查询",
                  "type":"HTTP",
                  "riskLevel":"MEDIUM",
                  "mcpPublished":%s,
                  "httpConfig":{
                    "method":"POST",
                    "urlTemplate":"https://api.example.test/orders/{id}",
                    "parameters":[
                      {"id":"id","name":"id","dataType":"STRING","requestLocation":"PATH","required":true,"defaultValue":"example"},
                      {"id":"items","name":"items","dataType":"ARRAY","requestLocation":"BODY","required":true},
                      {"id":"item","parentId":"items","dataType":"OBJECT"},
                      {"id":"name","parentId":"item","name":"name","dataType":"OBJECT","required":true,"defaultValue":{"kind":"primary"}}
                    ],
                    "secretHeaders":{"X-Api-Key":"secret/integration/api-key"},
                    "timeoutMillis":1000
                  }
                }
                """.formatted(name, mcpPublished);
    }

    /**
     * 验证或支持 {@code httpUpdateRequest} 所描述的测试场景。
     *
     * @param name 测试对象名称
     */
    private String httpUpdateRequest(String name) {
        return """
                {
                  "name":"%s",
                  "description":"更新后的订单查询",
                  "type":"HTTP",
                  "riskLevel":"HIGH",
                  "enabled":false,
                  "mcpPublished":true,
                  "httpConfig":{
                    "method":"GET",
                    "urlTemplate":"https://api.example.test/updated/{id}",
                    "parameters":[{"id":"id","name":"id","dataType":"STRING","requestLocation":"PATH","required":true}],
                    "secretHeaders":{"Authorization":"secret/integration/updated-token"},
                    "timeoutMillis":2000
                  }
                }
                """.formatted(name);
    }

    /**
     * 验证或支持 {@code token} 所描述的测试场景。
     *
     * @param tenantId 测试租户标识
     * @param principalId 测试辅助方法使用的 principalId 参数
     */
    private String token(UUID tenantId, String principalId) {
        return jwtService.createToken(tenantId, principalId, "测试管理员", List.of("tool:read", "tool:grant"));
    }

    private static ToolController.HttpParameterDefinitionRequest parameterRequest() {
        return new ToolController.HttpParameterDefinitionRequest(
                "payload", "", "payload", com.cmagent.core.domain.HttpParameterDataType.STRING,
                com.cmagent.core.domain.HttpParameterLocation.BODY, "", false,
                null, null, List.of(), null, null, null, null, null, null, false
        );
    }

    /**
     * 验证或支持 {@code token} 所描述的测试场景。
     *
     * @param tenantId 测试租户标识
     * @param principalId 测试辅助方法使用的 principalId 参数
     * @param permissions 测试辅助方法使用的 permissions 参数
     */
    private String token(UUID tenantId, String principalId, List<String> permissions) {
        return jwtService.createToken(tenantId, principalId, "测试主体", permissions);
    }

    /**
     * 验证或支持 {@code bearer} 所描述的测试场景。
     *
     * @param token 测试辅助方法使用的 token 参数
     */
    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
