package com.cmagent.server.web;

import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.jayway.jsonpath.JsonPath;
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
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 覆盖技能依赖映射与预检接口的权限、租户隔离和错误码。
 *
 * <p>用例断言响应不会返回 HTTP 端点、请求头或授权内部字段。</p>
 */
@SpringBootTest(classes = CmAgentServerApplication.class, properties = {
        "cm-agent.skills.enabled=true",
        "cm-agent.persistence.mode=memory"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class SkillDependencyControllerTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    @Test
    void 只有读取权限不能修改映射且写操作要求技能写权限() throws Exception {
        String skillId = createSkill();
        mockMvc.perform(put("/api/skills/{id}/dependencies/order-query", skillId)
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:read"))
                        .contentType("application/json")
                        .content("{\"toolId\":\"" + UUID.randomUUID() + "\",\"expectedRevision\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 映射缺失返回已映射依赖状态且不泄露端点() throws Exception {
        String skillId = createSkill();
        String versionId = JsonPath.read(
                mockMvc.perform(get("/api/skills/{id}", skillId)
                                .header("Authorization", "Bearer " + token(TENANT_ID, "skill:read")))
                        .andReturn().getResponse().getContentAsString(),
                "$.summary.candidateVersionId");

        mockMvc.perform(get("/api/skills/{id}/dependencies", skillId)
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.items[0].logicalKey").value("order-query"))
                .andExpect(jsonPath("$.items[0].required").value(true))
                .andExpect(jsonPath("$.items[0].mappedToolId").isEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("api.example.test"))));

        String checkId = JsonPath.read(
                mockMvc.perform(post("/api/skills/{id}/preflights", skillId)
                                .param("versionId", versionId)
                                .param("scope", "STRUCTURAL")
                                .header("Authorization", "Bearer " + token(TENANT_ID, "skill:write", "skill:read")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.check.status").value("FAILED"))
                        .andExpect(jsonPath("$.items[0].status").value("MAPPING_MISSING"))
                        .andExpect(jsonPath("$.items[0].errorCode").value("SKILL_DEPENDENCY_UNMAPPED"))
                        .andReturn().getResponse().getContentAsString(),
                "$.check.id");

        mockMvc.perform(get("/api/skills/{id}/preflights/{checkId}", skillId, checkId)
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("MAPPING_MISSING"));
    }

    @Test
    void 映射修订冲突返回409且客户端租户字段被忽略() throws Exception {
        String skillId = createSkill();

        mockMvc.perform(put("/api/skills/{id}/dependencies/order-query", skillId)
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:write"))
                        .contentType("application/json")
                        .content("{\"toolId\":\"" + UUID.randomUUID() + "\",\"expectedRevision\":5,"
                                + "\"tenantId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKILL_PREFLIGHT_STALE"))
                .andExpect(jsonPath("$.errorId").isNotEmpty());
    }

    @Test
    void 跨租户技能在依赖和预检接口统一隐藏() throws Exception {
        String skillId = createSkill();
        String token = token(UUID.randomUUID(), "skill:write", "skill:read");

        mockMvc.perform(get("/api/skills/{id}/dependencies", skillId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SKILL_NOT_FOUND"));

        mockMvc.perform(post("/api/skills/{id}/preflights", skillId)
                        .param("versionId", UUID.randomUUID().toString())
                        .param("scope", "STRUCTURAL")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SKILL_NOT_FOUND"));
    }

    @Test
    void 成功映射一次只递增一次修订且响应不含端点或请求头() throws Exception {
        String skillId = createSkill();
        String toolId = createLocalTool();

        mockMvc.perform(put("/api/skills/{id}/dependencies/order-query", skillId)
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:write", "skill:read"))
                        .contentType("application/json")
                        .content("{\"toolId\":\"" + toolId + "\",\"expectedRevision\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.items[0].logicalKey").value("order-query"))
                .andExpect(jsonPath("$.items[0].mappedToolId").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("urlTemplate"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secretHeaders"))));

        mockMvc.perform(put("/api/skills/{id}/dependencies/order-query", skillId)
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:write", "skill:read"))
                        .contentType("application/json")
                        .content("{\"toolId\":\"" + toolId + "\",\"expectedRevision\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKILL_PREFLIGHT_STALE"));
    }

    private String createLocalTool() throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/tools")
                        .header("Authorization", "Bearer " + token(TENANT_ID, "tool:grant"))
                        .contentType("application/json")
                        .content("""
                                {"name":"order-query-tool","description":"订单查询","type":"LOCAL","riskLevel":"LOW"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String createSkill() throws Exception {
        return JsonPath.read(mockMvc.perform(multipart("/api/skills")
                        .file(skillZip("order-skill"))
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:write", "skill:read")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.summary.id");
    }

    private String token(UUID tenantId, String... permissions) {
        return jwtService.createToken(tenantId, "skill-admin", "技能管理员", List.of(permissions));
    }

    private static MockMultipartFile skillZip(String name) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            write(out, "SKILL.md", "---\nname: " + name + "\ndescription: 订单分析\n"
                    + "dependencies:\n  tools:\n    - key: order-query\n      required: true\n"
                    + "      description: 查询订单\n---\n正文");
            write(out, "references/guide.md", "指南");
        }
        return new MockMultipartFile("file", name + ".zip", "application/zip", bytes.toByteArray());
    }

    private static void write(ZipOutputStream out, String path, String content) throws Exception {
        out.putNextEntry(new ZipEntry(path));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
