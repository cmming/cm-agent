package com.cmagent.server.web;

import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillVersionRepository;
import com.cmagent.core.repository.SkillResourceRepository;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.SkillPackageParser;
import com.cmagent.server.config.SkillProperties;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SkillManagementJdbcPersistenceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    static final JdbcDatabaseContainer<?> database = testDatabase();

    // 同一 API 合同可由验收命令在两种隔离数据库上重放；容器由 Testcontainers 创建和关闭。
    private static JdbcDatabaseContainer<?> testDatabase() {
        return switch (System.getProperty("cm-agent.test.skill-database", "postgresql")) {
            case "postgresql" -> new PostgreSQLContainer<>("postgres:16-alpine");
            case "mysql" -> new MySQLContainer<>("mysql:8.4");
            default -> throw new IllegalArgumentException("技能导入测试只支持 postgresql 或 mysql");
        };
    }

    @DynamicPropertySource
    static void jdbcProperties(DynamicPropertyRegistry registry) {
        registry.add("cm-agent.skills.enabled", () -> "true");
        registry.add("cm-agent.skills.allowed-resource-types", () -> ".md,.txt,.json,.yaml,.yml,.csv,.py,.xsd,.xml");
        registry.add("cm-agent.skills.max-resource-bytes", () -> "262144");
        registry.add("cm-agent.persistence.mode", () -> "jdbc");
        registry.add("cm-agent.persistence.jdbc.url", database::getJdbcUrl);
        registry.add("cm-agent.persistence.jdbc.username", database::getUsername);
        registry.add("cm-agent.persistence.jdbc.password", database::getPassword);
        registry.add("cm-agent.persistence.jdbc.driver-class-name", database::getDriverClassName);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private SkillDefinitionRepository definitions;
    @Autowired private SkillVersionRepository versions;
    @Autowired private SkillResourceRepository resources;
    @Autowired private SkillPackageParser parser;
    @Autowired private SkillProperties properties;

    @Test
    void 长描述和大资源可以完整导入而超长描述在落库前被拒绝() throws Exception {
        String description = "中".repeat(1023) + "😀";
        assertImported("long-description", largeSkillArchive(description), 1, description);
        mockMvc.perform(multipart("/api/skills")
                        .file(new MockMultipartFile("file", "invalid.zip", "application/zip", largeSkillArchive(description + "a")))
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SKILL_PACKAGE_INVALID"))
                .andExpect(jsonPath("$.message").value("技能描述长度或字符不合法"))
                .andExpect(jsonPath("$.errorId").isNotEmpty());
    }

    private static byte[] largeSkillArchive(String description) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry("SKILL.md"));
            out.write(("---\nname: long-description\ndescription: " + description + "\n---\n正文")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("schemas/sample.xsd"));
            out.write("x".repeat(242277).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    @EnabledIfSystemProperty(named = "cm-agent.test.skill-packages-dir", matches = ".+")
    void 外部真实技能包可以经接口完整落库() throws Exception {
        // 原版第三方包由验收命令提供，不进入版本库；只写 Testcontainers 的隔离数据库，不执行脚本。
        Path directory = Path.of(System.getProperty("cm-agent.test.skill-packages-dir"));
        for (String name : List.of("docx", "pptx", "pdf")) {
            assertImported(name, Files.readAllBytes(directory.resolve(name + ".zip")),
                    name.equals("docx") ? 60 : name.equals("pptx") ? 55 : 11, null);
        }
    }

    private void assertImported(String name, byte[] archive, int resourceCount, String expectedDescription) throws Exception {
        var parsed = parser.parse(new ByteArrayInputStream(archive), properties.toPackageLimits());
        String body = mockMvc.perform(multipart("/api/skills")
                        .file(new MockMultipartFile("file", name + ".zip", "application/zip", archive))
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID skillId = UUID.fromString(JsonPath.read(body, "$.summary.id"));
        UUID candidateId = UUID.fromString(JsonPath.read(body, "$.summary.candidateVersionId"));
        var version = versions.find(TENANT_ID, skillId, candidateId).orElseThrow();
        assertThat(version.description()).isEqualTo(parsed.description());
        if (expectedDescription != null) assertThat(version.description()).isEqualTo(expectedDescription);
        assertThat(resources.list(TENANT_ID, skillId, candidateId)).hasSize(resourceCount).allSatisfy(resource -> {
            assertThat(resource.content()).isEqualTo(parsed.resources().get(resource.path()));
            assertThat(resource.byteLength()).isEqualTo(resource.content().getBytes(StandardCharsets.UTF_8).length);
        });
        assertThat(definitions.find(TENANT_ID, skillId).orElseThrow().publishedVersionId()).isNull();
        mockMvc.perform(get("/api/skills/{id}", skillId).header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.description").value(version.description()));
    }

    @Test
    void 候选替换指针冲突不会追加版本或改变候选指针() throws Exception {
        String created = mockMvc.perform(multipart("/api/skills")
                        .file(skillZip("第一版"))
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String skillId = JsonPath.read(created, "$.summary.id");
        String candidateId = JsonPath.read(created, "$.summary.candidateVersionId");

        mockMvc.perform(multipart("/api/skills/{id}/versions", skillId)
                        .file(skillZip("第二版"))
                        .param("expectedCandidateVersionId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKILL_CANDIDATE_CONFLICT"));

        SkillDefinition current = definitions.find(TENANT_ID, UUID.fromString(skillId)).orElseThrow();
        assertThat(current.candidateVersionId()).isEqualTo(UUID.fromString(candidateId));
        assertThat(versions.list(TENANT_ID, UUID.fromString(skillId))).hasSize(1);
    }

    private String token() {
        return jwtService.createToken(TENANT_ID, "jdbc-admin", "JDBC 管理员",
                List.of("skill:write", "skill:read"));
    }

    private static MockMultipartFile skillZip(String body) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry("SKILL.md"));
            out.write(("---\nname: rollback-skill\ndescription: 回滚测试\n---\n" + body)
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("references/guide.md"));
            out.write("资源".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return new MockMultipartFile("file", "rollback.zip", "application/zip", bytes.toByteArray());
    }
}
