package com.cmagent.server.web;

import com.cmagent.core.audit.AuditEvent;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.store.InMemoryPlatformStore;
import com.cmagent.server.runtime.ModelCredentialCipher;
import com.cmagent.server.service.ModelCatalogDiscoveryException;
import com.cmagent.server.service.ModelCatalogDiscoveryService;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ModelConfigControllerTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DEFAULT_MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private InMemoryPlatformStore store;
    @Autowired
    private ModelCredentialCipher credentialCipher;
    @MockBean
    private ModelCatalogDiscoveryService catalogDiscoveryService;

    @Test
    void 完整Crud保持租户隔离且不暴露密钥字段() throws Exception {
        String token = token(TENANT_A, List.of("model:read", "model:write", "model:delete"));
        String createdBody = mockMvc.perform(post("/api/model-configs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("OPENAI_COMPATIBLE", "业务模型", "https://models.example.test/v1", "qwen-plus", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("业务模型"))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.encryptedApiKey").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(createdBody, "$.id");
        String storedCiphertext = store.findEncryptedModelApiKey(TENANT_A, UUID.fromString(id)).orElseThrow();
        assertThat(storedCiphertext).isNotEqualTo("unit-test-model-key").doesNotContain("unit-test-model-key");
        assertThat(credentialCipher.decrypt(storedCiphertext)).isEqualTo("unit-test-model-key");

        mockMvc.perform(get("/api/model-configs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(id)))
                .andExpect(jsonPath("$[*].apiKey").doesNotExist());

        mockMvc.perform(get("/api/model-configs/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelName").value("qwen-plus"));

        mockMvc.perform(put("/api/model-configs/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(
                                "DASHSCOPE_NATIVE", "业务模型（更新）", "https://dashscope.example.test/api",
                                "qwen-max", false, "unit-test-rotated-model-key")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerType").value("DASHSCOPE_NATIVE"))
                .andExpect(jsonPath("$.enabled").value(false));
        assertThat(credentialCipher.decrypt(store.findEncryptedModelApiKey(TENANT_A, UUID.fromString(id)).orElseThrow()))
                .isEqualTo("unit-test-rotated-model-key");

        mockMvc.perform(put("/api/model-configs/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerType":"DASHSCOPE_NATIVE","displayName":"业务模型（仅元数据更新）",
                                "baseUrl":"https://dashscope.example.test/api","modelName":"qwen-max","enabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        assertThat(credentialCipher.decrypt(store.findEncryptedModelApiKey(TENANT_A, UUID.fromString(id)).orElseThrow()))
                .isEqualTo("unit-test-rotated-model-key");

        String otherTenantToken = token(TENANT_B, List.of("model:read"));
        mockMvc.perform(get("/api/model-configs/{id}", id)
                        .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/model-configs/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(store.listAuditEvents(TENANT_A).stream().map(AuditEvent::eventType))
                .contains("MODEL_CONFIG_CREATE", "MODEL_CONFIG_UPDATE", "MODEL_CONFIG_DELETE");
    }

    @Test
    void 无权限访问写入拒绝审计() throws Exception {
        String token = token(TENANT_A, List.of("agent:read"));

        mockMvc.perform(get("/api/model-configs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(store.listAuditEvents(TENANT_A).stream())
                .anyMatch(event -> "ACCESS_DENIED".equals(event.eventType())
                        && "MODEL_CONFIG".equals(event.resourceType()));
    }

    @Test
    void 删除仍被Agent引用的配置返回明确冲突() throws Exception {
        String token = token(TENANT_A, List.of("agent:write", "model:delete"));
        mockMvc.perform(post("/api/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"引用模型的 Agent","systemPrompt":"帮助用户","modelName":"qwen-max"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/model-configs/{id}", DEFAULT_MODEL_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("模型配置仍被 Agent 引用，请先调整 Agent 后再删除"));
    }

    @Test
    void 未被引用的系统默认配置仍不可删除() throws Exception {
        String token = token(TENANT_A, List.of("model:delete"));

        mockMvc.perform(delete("/api/model-configs/{id}", DEFAULT_MODEL_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("系统默认模型配置不能删除，可停用或更新"));
    }

    @Test
    void 非法地址返回脱敏参数错误且不落库() throws Exception {
        String token = token(TENANT_A, List.of("model:read", "model:write"));

        mockMvc.perform(post("/api/model-configs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("OPENAI_COMPATIBLE", "非法模型", "file:///secret/path", "model", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("请求参数不合法"));

        assertThat(store.listModelConfigs(TENANT_A)).hasSize(1);
    }

    @Test
    void 模型目录接口仅返回名称且使用写权限() throws Exception {
        String token = token(TENANT_A, List.of("model:write"));
        when(catalogDiscoveryService.discoverDraft(any(), any(), any(), any(), any()))
                .thenReturn(List.of("gpt-mini", "gpt-pro"));

        mockMvc.perform(post("/api/model-configs/discover-models")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerType":"OPENAI_COMPATIBLE","baseUrl":"https://api.openai.com/v1","apiKey":"unit-test-model-key"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]").value("gpt-mini"))
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        mockMvc.perform(post("/api/model-configs/discover-models")
                        .header("Authorization", "Bearer " + token(TENANT_A, List.of("model:read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerType":"OPENAI_COMPATIBLE","baseUrl":"https://api.openai.com/v1","apiKey":"unit-test-model-key"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 模型目录失败返回稳定错误码和关联编号且不回显密钥() throws Exception {
        String token = token(TENANT_A, List.of("model:write"));
        ModelCatalogDiscoveryException failure = new ModelCatalogDiscoveryException(
                HttpStatus.BAD_GATEWAY,
                com.cmagent.api.ApiErrorCode.MODEL_DISCOVERY_UPSTREAM_ERROR,
                "模型目录获取失败，请稍后重试或手动填写模型名称",
                new ErrorDiagnosticLogger.DiagnosticContext("catalog-web-001", "MODEL_CATALOG_DISCOVERY",
                        "MODEL_DISCOVERY_UPSTREAM_ERROR", TENANT_A.toString(), "model-admin", "-", "-", "draft", "-",
                        "MODEL_CATALOG_DISCOVERY"),
                new IllegalStateException("Authorization: Bearer unit-test-model-key")
        );
        when(catalogDiscoveryService.discoverDraft(any(), any(), any(), any(), any())).thenThrow(failure);

        mockMvc.perform(post("/api/model-configs/discover-models")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", "catalog-web-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerType":"OPENAI_COMPATIBLE","baseUrl":"https://api.openai.com/v1","apiKey":"unit-test-model-key"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("MODEL_DISCOVERY_UPSTREAM_ERROR"))
                .andExpect(jsonPath("$.errorId").value("catalog-web-001"))
                .andExpect(jsonPath("$.message").value("模型目录获取失败，请稍后重试或手动填写模型名称"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unit-test-model-key"))));
    }

    private String token(UUID tenantId, List<String> permissions) {
        return jwtService.createToken(tenantId, "model-admin", "模型管理员", permissions);
    }

    private static String requestBody(
            String providerType, String displayName, String baseUrl, String modelName, boolean enabled
    ) {
        return requestBody(providerType, displayName, baseUrl, modelName, enabled, "unit-test-model-key");
    }

    private static String requestBody(
            String providerType, String displayName, String baseUrl, String modelName, boolean enabled, String apiKey
    ) {
        return """
                {"providerType":"%s","displayName":"%s","baseUrl":"%s","modelName":"%s","enabled":%s,"apiKey":"%s"}
                """.formatted(providerType, displayName, baseUrl, modelName, enabled, apiKey);
    }
}
