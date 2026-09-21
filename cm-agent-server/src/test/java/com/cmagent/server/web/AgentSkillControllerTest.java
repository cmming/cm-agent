package com.cmagent.server.web;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.AgentSkillBindingRepository;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class, properties = {
        "cm-agent.skills.enabled=true", "cm-agent.persistence.mode=memory"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AgentSkillControllerTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000771");

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private AgentDefinitionRepository agents;
    @Autowired private AgentSkillBindingRepository bindings;

    @BeforeEach
    void createAgent() {
        agents.save(new AgentDefinition(
                AGENT_ID, TENANT_ID, "绑定测试", "", "协助用户", null, "fake-model",
                0.2d, 6, true, List.of(), "tester", "tester"));
    }

    @Test
    void 绑定必须同时具有Agent写和技能读权限且重复绑定幂等() throws Exception {
        String skillId = createAndEnableSkill();
        String missingSkillRead = token("agent:write");
        mockMvc.perform(put("/api/agents/{agentId}/skills/{skillId}", AGENT_ID, skillId)
                        .header("Authorization", "Bearer " + missingSkillRead))
                .andExpect(status().isForbidden());

        String token = token("agent:read", "agent:write", "skill:read");
        String first = mockMvc.perform(put("/api/agents/{agentId}/skills/{skillId}", AGENT_ID, skillId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(put("/api/agents/{agentId}/skills/{skillId}", AGENT_ID, skillId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(JsonPath.<String>read(second, "$.bindingId"))
                .isEqualTo(JsonPath.read(first, "$.bindingId"));

        mockMvc.perform(get("/api/agents/{agentId}/skills", AGENT_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillId").value(skillId));

        mockMvc.perform(delete("/api/agents/{agentId}/skills/{skillId}", AGENT_ID, skillId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void 删除Agent会在同一流程清理技能绑定() throws Exception {
        String skillId = createAndEnableSkill();
        String bindToken = token("agent:read", "agent:write", "skill:read");
        mockMvc.perform(put("/api/agents/{agentId}/skills/{skillId}", AGENT_ID, skillId)
                        .header("Authorization", "Bearer " + bindToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/agents/{id}", AGENT_ID)
                        .header("Authorization", "Bearer " + token("agent:delete")))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(bindings.list(TENANT_ID, AGENT_ID)).isEmpty();
    }

    private String createAndEnableSkill() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry("SKILL.md"));
            out.write("---\nname: bindable\ndescription: 可绑定技能\n---\n正文".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        String created = mockMvc.perform(multipart("/api/skills")
                        .file(new MockMultipartFile("file", "skill.zip", "application/zip", bytes.toByteArray()))
                        .header("Authorization", "Bearer " + token("skill:write", "skill:read")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String skillId = JsonPath.read(created, "$.summary.id");
        mockMvc.perform(put("/api/skills/{id}/enabled", skillId)
                        .header("Authorization", "Bearer " + token("skill:write"))
                        .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        return skillId;
    }

    private String token(String... permissions) {
        return jwtService.createToken(TENANT_ID, "tester", "测试用户", List.of(permissions));
    }
}
