package com.cmagent.server.web;

import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.config.SkillProperties;
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

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class, properties = {
        "cm-agent.skills.enabled=true",
        "cm-agent.persistence.mode=memory"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class SkillControllerTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private SkillProperties properties;

    @Test
    void 只有运行权限不能上传技能() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "invalid.zip", "application/zip", new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/skills").file(file)
                        .header("Authorization", "Bearer " + token(TENANT_ID, "agent:run", "agent:read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 创建查询启用并拒绝过期版本更新() throws Exception {
        MockMultipartFile file = file("support", "支持流程", "第一版", Map.of("references/guide.md", "操作指南"));
        String created = mockMvc.perform(multipart("/api/skills").file(file)
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:write", "skill:read")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.enabled").value(false))
                .andExpect(jsonPath("$.summary.versionNo").value(1))
                .andReturn().getResponse().getContentAsString();
        String skillId = JsonPath.read(created, "$.summary.id");
        String versionId = JsonPath.read(created, "$.summary.currentVersionId");

        mockMvc.perform(get("/api/skills/{id}", skillId)
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.name").value("support"))
                .andExpect(jsonPath("$.resources[0].path").value("references/guide.md"));

        mockMvc.perform(put("/api/skills/{id}/enabled", skillId)
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:write"))
                        .contentType("application/json")
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(multipart("/api/skills/{id}/versions", skillId)
                        .file(file("support", "支持流程", "第二版", Map.of()))
                        .param("expectedVersionId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:write")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKILL_CONFLICT"));

        mockMvc.perform(multipart("/api/skills/{id}/versions", skillId)
                        .file(file("support", "支持流程", "第二版", Map.of()))
                        .param("expectedVersionId", versionId)
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:write")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.versionNo").value(2));
    }

    @Test
    void 非法包返回技能错误且跨租户隐藏资源() throws Exception {
        MockMultipartFile invalid = new MockMultipartFile(
                "file", "invalid.zip", "application/zip", new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/skills").file(invalid)
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:write")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SKILL_PACKAGE_INVALID"))
                .andExpect(jsonPath("$.errorId").isNotEmpty());

        String created = mockMvc.perform(multipart("/api/skills")
                        .file(file("private", "租户技能", "正文", Map.of()))
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:write")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String skillId = JsonPath.read(created, "$.summary.id");
        mockMvc.perform(get("/api/skills/{id}", skillId)
                        .header("Authorization", "Bearer " + token(UUID.randomUUID(), "skill:read")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SKILL_NOT_FOUND"));
    }

    @Test
    void 同租户同名技能被拒绝且非Zip媒体类型返回技能错误() throws Exception {
        String token = token(TENANT_ID, "skill:write", "skill:read");
        mockMvc.perform(multipart("/api/skills")
                        .file(file("duplicate", "首次上传", "正文", Map.of()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/skills")
                        .file(file("duplicate", "重复上传", "另一份正文", Map.of()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKILL_CONFLICT"));

        MockMultipartFile text = new MockMultipartFile(
                "file", "not-zip.txt", "text/plain", "普通文本".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/skills").file(text)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SKILL_PACKAGE_INVALID"))
                .andExpect(jsonPath("$.message").value("技能包必须是 ZIP 文件"));
    }

    @Test
    void 功能关闭后仍可读取历史并停用技能但拒绝新增() throws Exception {
        String token = token(TENANT_ID, "skill:write", "skill:read");
        String created = mockMvc.perform(multipart("/api/skills")
                        .file(file("history", "历史技能", "历史正文", Map.of()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String skillId = JsonPath.read(created, "$.summary.id");
        mockMvc.perform(put("/api/skills/{id}/enabled", skillId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        properties.setEnabled(false);
        mockMvc.perform(get("/api/skills/capabilities")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(get("/api/skills/{id}", skillId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("历史正文"));
        mockMvc.perform(multipart("/api/skills")
                        .file(file("blocked", "关闭后上传", "正文", Map.of()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SKILL_FEATURE_DISABLED"));
        mockMvc.perform(put("/api/skills/{id}/enabled", skillId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void 能力接口返回公开限制() throws Exception {
        mockMvc.perform(get("/api/skills/capabilities")
                        .header("Authorization", "Bearer " + token(TENANT_ID, "skill:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.allowedExtensions", hasItem(".html")))
                .andExpect(jsonPath("$.maxBoundSkills").value(20));
    }

    private String token(UUID tenantId, String... permissions) {
        return jwtService.createToken(tenantId, "skill-admin", "技能管理员", List.of(permissions));
    }

    private static MockMultipartFile file(
            String name, String description, String content, Map<String, String> resources) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            write(out, "SKILL.md", "---\nname: " + name + "\ndescription: " + description + "\n---\n" + content);
            for (Map.Entry<String, String> resource : resources.entrySet()) {
                write(out, resource.getKey(), resource.getValue());
            }
        }
        return new MockMultipartFile("file", name + ".zip", "application/zip", bytes.toByteArray());
    }

    private static void write(ZipOutputStream out, String path, String content) throws Exception {
        out.putNextEntry(new ZipEntry(path));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
