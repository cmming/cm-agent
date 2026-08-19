package com.cmagent.server.web;

import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.store.InMemoryPlatformStore;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AgentControllerTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEFAULT_MODEL_CONFIG_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private InMemoryPlatformStore store;

    @Test
    void 从模型配置创建更新并删除Agent() throws Exception {
        UUID alternativeModelId = UUID.fromString("00000000-0000-0000-0000-000000000302");
        store.saveModelConfig(new ModelConfig(
                alternativeModelId, TENANT_ID, ModelProviderType.DASHSCOPE_NATIVE,
                "通义生产配置", "https://dashscope.example.test/api", "qwen-plus", true
        ));
        String token = token("agent:read", "agent:write", "agent:delete");

        String createdBody = mockMvc.perform(post("/api/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"订单助手","systemPrompt":"处理订单","modelConfigId":"%s","enabled":true}
                                """.formatted(DEFAULT_MODEL_CONFIG_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelProviderId").value(DEFAULT_MODEL_CONFIG_ID.toString()))
                .andExpect(jsonPath("$.modelName").value("qwen-max"))
                .andReturn().getResponse().getContentAsString();
        String agentId = JsonPath.read(createdBody, "$.id");
        store.saveGrant(new ToolGrant(
                TENANT_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                UUID.fromString(agentId),
                null,
                true
        ));

        mockMvc.perform(put("/api/agents/{id}", agentId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"订单助手（更新）","systemPrompt":"处理售后订单","modelConfigId":"%s","enabled":false}
                                """.formatted(alternativeModelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("订单助手（更新）"))
                .andExpect(jsonPath("$.modelProviderId").value(alternativeModelId.toString()))
                .andExpect(jsonPath("$.modelName").value("qwen-plus"))
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(delete("/api/agents/{id}", agentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(store.findAgent(TENANT_ID, UUID.fromString(agentId))).isEmpty();
        assertThat(store.listGrants(TENANT_ID, UUID.fromString(agentId))).isEmpty();
        assertThat(store.listAuditEvents(TENANT_ID).stream().map(AuditEvent::eventType))
                .contains("AGENT_CREATE", "AGENT_UPDATE", "AGENT_DELETE");
    }

    @Test
    void 停用模型和已有运行历史的Agent均被安全拒绝() throws Exception {
        UUID disabledModelId = UUID.fromString("00000000-0000-0000-0000-000000000303");
        store.saveModelConfig(new ModelConfig(
                disabledModelId, TENANT_ID, ModelProviderType.OPENAI_COMPATIBLE,
                "已停用配置", "https://models.example.test/v1", "qwen-disabled", false
        ));
        String token = token("agent:read", "agent:write", "agent:delete");

        mockMvc.perform(post("/api/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"不可创建","systemPrompt":"帮助用户","modelConfigId":"%s","enabled":true}
                                """.formatted(disabledModelId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("所选模型配置已停用"));

        String createdBody = mockMvc.perform(post("/api/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"保留历史","systemPrompt":"帮助用户","modelConfigId":"%s","enabled":true}
                                """.formatted(DEFAULT_MODEL_CONFIG_ID)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID agentId = UUID.fromString(JsonPath.read(createdBody, "$.id"));
        store.save(TENANT_ID, RunRecord.create(
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                TENANT_ID, agentId, "agent-admin", "测试运行", Instant.parse("2026-08-18T00:00:00Z")
        ));

        mockMvc.perform(delete("/api/agents/{id}", agentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Agent 已有运行或会话历史，不能删除；请停用该 Agent"));
    }

    private String token(String... permissions) {
        return jwtService.createToken(TENANT_ID, "agent-admin", "Agent 管理员", List.of(permissions));
    }
}
