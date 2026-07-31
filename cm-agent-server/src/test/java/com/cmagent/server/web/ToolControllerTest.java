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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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

    @SpyBean
    private AuditAppender auditAppender;

    @Test
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
    void httpCreateCanonicalizesNestedJsonAndExposesOnlySecretReferences() throws Exception {
        String token = token(TENANT_A, "admin");
        String response = mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(httpRequest("orders", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("HTTP"))
                .andExpect(jsonPath("$.endpoint").value("https://api.example.test/orders/{id}"))
                .andExpect(jsonPath("$.inputSchema").value("{\"properties\":{\"id\":{\"type\":\"string\"},\"items\":{\"items\":{\"properties\":{\"name\":{\"type\":\"object\"}},\"type\":\"object\"},\"type\":\"array\"}},\"type\":\"object\"}"))
                .andExpect(jsonPath("$.httpConfig.method").value("POST"))
                .andExpect(jsonPath("$.httpConfig.urlTemplate").value("https://api.example.test/orders/{id}"))
                .andExpect(jsonPath("$.httpConfig.inputSchema").value("{\"properties\":{\"id\":{\"type\":\"string\"},\"items\":{\"items\":{\"properties\":{\"name\":{\"type\":\"object\"}},\"type\":\"object\"},\"type\":\"array\"}},\"type\":\"object\"}"))
                .andExpect(jsonPath("$.httpConfig.parameterMappings[0].defaultValueJson").value("{\"kind\":\"primary\"}"))
                .andExpect(jsonPath("$.httpConfig.secretHeaders.X-Api-Key").value("secret/integration/api-key"))
                .andExpect(jsonPath("$.httpConfig.timeoutMillis").value(1000))
                .andExpect(jsonPath("$.mcpPublished").value(true))
                // 默认 HTTP 执行开关关闭时，配置可保存但运行时不能标记为就绪。
                .andExpect(jsonPath("$.runtimeReady").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String toolId = JsonPath.read(response, "$.id");

        HttpToolConfig config = store.findHttpToolConfig(TENANT_A, UUID.fromString(toolId)).orElseThrow();
        assertThat(config.inputSchema()).isEqualTo("{\"properties\":{\"id\":{\"type\":\"string\"},\"items\":{\"items\":{\"properties\":{\"name\":{\"type\":\"object\"}},\"type\":\"object\"},\"type\":\"array\"}},\"type\":\"object\"}");
        assertThat(config.parameterMappings()).hasSize(2).first()
                .extracting(mapping -> mapping.defaultValueJson())
                .isEqualTo("{\"kind\":\"primary\"}");
        assertThat(store.findMcpToolPublication(TENANT_A, UUID.fromString(toolId))).isPresent();
    }

    @Test
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
                                 "httpConfig":{"method":"POST","urlTemplate":"https://api.example.test","inputSchema":{},"timeoutMillis":1000}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void httpCreateRejectsNullParameterMapping() throws Exception {
        String token = token(TENANT_A, "admin");

        mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"null-mapping","description":"空映射","type":"HTTP","riskLevel":"LOW",
                                 "httpConfig":{"method":"POST","urlTemplate":"https://api.example.test","inputSchema":{},
                                 "parameterMappings":[null],"timeoutMillis":1000}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
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
                        "{\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"],\"type\":\"object\"}"))
                .andExpect(jsonPath("$.httpConfig.method").value("GET"))
                .andExpect(jsonPath("$.httpConfig.urlTemplate").value("https://api.example.test/updated/{id}"))
                .andExpect(jsonPath("$.httpConfig.secretHeaders.Authorization").value("secret/integration/updated-token"))
                .andExpect(jsonPath("$.httpConfig.timeoutMillis").value(2000))
                .andExpect(jsonPath("$.mcpPublished").value(true));
    }

    @Test
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

    private static ToolDefinition localTool(UUID id, String name) {
        return new ToolDefinition(
                id, TENANT_A, name, "", ToolType.LOCAL, "{\"type\":\"object\"}", ToolRiskLevel.LOW,
                true, "", "admin", "admin"
        );
    }

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

    private void grantTool(String token, String toolId, UUID agentId) throws Exception {
        mockMvc.perform(post("/api/tools/{id}/grants", toolId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"%s\"}".formatted(agentId)))
                .andExpect(status().isOk());
    }

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
                    "inputSchema":{"type":"object","properties":{"id":{"type":"string"},"items":{"type":"array","items":{"type":"object","properties":{"name":{"type":"object"}}}}}},
                    "parameterMappings":[{
                      "sourcePointer":"/items/0/name",
                      "location":"BODY",
                      "targetPointer":"/payload/items/0/name",
                      "required":true,
                      "defaultValue":{"kind":"primary"}
                    },{
                      "sourcePointer":"/id",
                      "location":"PATH",
                      "targetName":"id",
                      "required":true,
                      "defaultValue":"example"
                    }],
                    "secretHeaders":{"X-Api-Key":"secret/integration/api-key"},
                    "timeoutMillis":1000
                  }
                }
                """.formatted(name, mcpPublished);
    }

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
                    "inputSchema":{"required":["id"],"type":"object","properties":{"id":{"type":"string"}}},
                    "parameterMappings":[{
                      "sourcePointer":"/id",
                      "location":"PATH",
                      "targetName":"id",
                      "required":true
                    }],
                    "secretHeaders":{"Authorization":"secret/integration/updated-token"},
                    "timeoutMillis":2000
                  }
                }
                """.formatted(name);
    }

    private String token(UUID tenantId, String principalId) {
        return jwtService.createToken(tenantId, principalId, "测试管理员", List.of("tool:read", "tool:grant"));
    }

    private String token(UUID tenantId, String principalId, List<String> permissions) {
        return jwtService.createToken(tenantId, principalId, "测试主体", permissions);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
