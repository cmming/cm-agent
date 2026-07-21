package com.cmagent.server.web;

import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.store.InMemoryPlatformStore;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                .andExpect(jsonPath("$[0].mcpPublished").value(false));
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
                .andExpect(jsonPath("$.inputSchema").value("{\"properties\":{\"items\":{\"items\":{\"type\":\"object\"},\"type\":\"array\"}},\"type\":\"object\"}"))
                .andExpect(jsonPath("$.httpConfig.method").value("POST"))
                .andExpect(jsonPath("$.httpConfig.urlTemplate").value("https://api.example.test/orders/{id}"))
                .andExpect(jsonPath("$.httpConfig.inputSchema").value("{\"properties\":{\"items\":{\"items\":{\"type\":\"object\"},\"type\":\"array\"}},\"type\":\"object\"}"))
                .andExpect(jsonPath("$.httpConfig.parameterMappings[0].defaultValueJson").value("{\"kind\":\"primary\"}"))
                .andExpect(jsonPath("$.httpConfig.secretHeaders.X-Api-Key").value("secret/integration/api-key"))
                .andExpect(jsonPath("$.httpConfig.timeoutMillis").value(1000))
                .andExpect(jsonPath("$.mcpPublished").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String toolId = JsonPath.read(response, "$.id");

        HttpToolConfig config = store.findHttpToolConfig(TENANT_A, UUID.fromString(toolId)).orElseThrow();
        assertThat(config.inputSchema()).isEqualTo("{\"properties\":{\"items\":{\"items\":{\"type\":\"object\"},\"type\":\"array\"}},\"type\":\"object\"}");
        assertThat(config.parameterMappings()).singleElement()
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
        doAnswer(invocation -> {
            if ("TOOL_CREATE".equals(invocation.getArgument(2, String.class))) {
                throw new AuditPersistenceException("审计写入失败", new IllegalStateException("audit unavailable"));
            }
            return invocation.callRealMethod();
        }).when(auditAppender).append(
                any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(httpRequest("audit-failure", true)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));

        assertThat(store.listTools(TENANT_A)).isEmpty();
        assertThat(store.listEnabledMcpToolPublications(TENANT_A)).isEmpty();
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
                    "inputSchema":{"type":"object","properties":{"items":{"type":"array","items":{"type":"object"}}}},
                    "parameterMappings":[{
                      "sourcePointer":"/items/0/name",
                      "location":"BODY",
                      "targetPointer":"/payload/items/0/name",
                      "required":true,
                      "defaultValue":{"kind":"primary"}
                    }],
                    "secretHeaders":{"X-Api-Key":"secret/integration/api-key"},
                    "timeoutMillis":1000
                  }
                }
                """.formatted(name, mcpPublished);
    }

    private String token(UUID tenantId, String principalId) {
        return jwtService.createToken(tenantId, principalId, "测试管理员", List.of("tool:read", "tool:grant"));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
