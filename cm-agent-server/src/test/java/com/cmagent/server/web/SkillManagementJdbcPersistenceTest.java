package com.cmagent.server.web;

import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillVersionRepository;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void jdbcProperties(DynamicPropertyRegistry registry) {
        registry.add("cm-agent.skills.enabled", () -> "true");
        registry.add("cm-agent.persistence.mode", () -> "jdbc");
        registry.add("cm-agent.persistence.jdbc.url", postgres::getJdbcUrl);
        registry.add("cm-agent.persistence.jdbc.username", postgres::getUsername);
        registry.add("cm-agent.persistence.jdbc.password", postgres::getPassword);
        registry.add("cm-agent.persistence.jdbc.driver-class-name", postgres::getDriverClassName);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private SkillDefinitionRepository definitions;
    @Autowired private SkillVersionRepository versions;

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
