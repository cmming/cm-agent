package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.RunSkillSnapshotRepository;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证 JDBC Run 快照能跨当前版本更新保持历史技能版本。 */
@SpringBootTest(classes = CmAgentServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SkillRuntimeJdbcPersistenceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    static final JdbcDatabaseContainer<?> DATABASE = database();

    /**
     * 用同一套服务契约分别覆盖 PostgreSQL 和 MySQL，避免两个数据库的运行快照语义漂移。
     *
     * <p>默认选择 PostgreSQL；远程验证以 {@code -Dcm-agent.test.database=mysql} 切换到 MySQL 8.4。</p>
     */
    private static JdbcDatabaseContainer<?> database() {
        return "mysql".equals(System.getProperty("cm-agent.test.database"))
                ? new MySQLContainer<>("mysql:8.4")
                : new PostgreSQLContainer<>("postgres:16-alpine");
    }

    @DynamicPropertySource
    static void jdbcProperties(DynamicPropertyRegistry registry) {
        registry.add("cm-agent.skills.enabled", () -> "true");
        registry.add("cm-agent.persistence.mode", () -> "jdbc");
        registry.add("cm-agent.persistence.jdbc.url", DATABASE::getJdbcUrl);
        registry.add("cm-agent.persistence.jdbc.username", DATABASE::getUsername);
        registry.add("cm-agent.persistence.jdbc.password", DATABASE::getPassword);
        registry.add("cm-agent.persistence.jdbc.driver-class-name", DATABASE::getDriverClassName);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private RunRepository runs;
    @Autowired private RunSkillSnapshotRepository snapshots;
    @Autowired private SkillRuntimeService runtimeSkills;

    @Test
    /** 验证 Run 已持久化的快照不会在技能更新后重建为当前版本。 */
    void jdbcRunSnapshotKeepsHistoricalVersionAfterSkillUpdate() throws Exception {
        String token = token();
        String agentId = createAgent(token);
        String created = mockMvc.perform(multipart("/api/skills")
                        .file(skillZip("jdbc-history", "第一版"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String skillId = JsonPath.read(created, "$.summary.id");
        String firstVersionId = JsonPath.read(created, "$.summary.currentVersionId");
        mockMvc.perform(put("/api/skills/{id}/enabled", skillId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/agents/{agentId}/skills/{skillId}", agentId, skillId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        String runResponse = mockMvc.perform(post("/api/agents/{agentId}/runs", agentId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"input\":\"固定版本\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(JsonPath.read(runResponse, "$.runId"));
        assertThat(snapshots.find(TENANT_ID, runId)).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.skills()).hasSize(1);
            assertThat(snapshot.skills().getFirst().versionId()).isEqualTo(UUID.fromString(firstVersionId));
        });

        mockMvc.perform(multipart("/api/skills/{id}/versions", skillId)
                        .file(skillZip("jdbc-history", "第二版"))
                        .param("expectedVersionId", firstVersionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        RunRecord run = runs.findByTenantAndAgentAndId(TENANT_ID, UUID.fromString(agentId), runId).orElseThrow();
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "runtime-jdbc", "JDBC 运行用户", Set.of(
                "agent:run", "agent:read", "agent:write", "skill:read", "skill:write"));
        assertThat(runtimeSkills.restore(principal, run).versions())
                .singleElement().satisfies(view -> {
                    assertThat(view.version().versionNo()).isEqualTo(1);
                    assertThat(view.version().content()).contains("第一版");
                });
    }

    private String createAgent(String token) throws Exception {
        String response = mockMvc.perform(post("/api/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"JDBC 技能 Agent\",\"systemPrompt\":\"测试\",\"modelName\":\"qwen-max\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String token() {
        return jwtService.createToken(TENANT_ID, "runtime-jdbc", "JDBC 运行用户", List.of(
                "agent:run", "agent:read", "agent:write", "skill:read", "skill:write"));
    }

    private static MockMultipartFile skillZip(String name, String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry("SKILL.md"));
            out.write(("---\nname: " + name + "\ndescription: JDBC 历史版本\n---\n" + content)
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return new MockMultipartFile("file", name + ".zip", "application/zip", bytes.toByteArray());
    }
}
