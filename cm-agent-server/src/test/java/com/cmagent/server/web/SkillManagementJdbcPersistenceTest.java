package com.cmagent.server.web;

import com.cmagent.api.ApiPageRequest;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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
    @SpyBean private AuditAppender auditAppender;

    @Test
    void 审计失败会回滚技能定义版本和资源() throws Exception {
        doThrow(new AuditPersistenceException("模拟审计故障", new IllegalStateException("jwt=secret")))
                .when(auditAppender).append(any(UUID.class), anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyString());

        mockMvc.perform(multipart("/api/skills")
                        .file(skillZip())
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.errorId").isNotEmpty())
                .andExpect(jsonPath("$.message").value("审计服务暂不可用"));

        assertThat(definitions.list(TENANT_ID, "rollback-skill", null, new ApiPageRequest(0, 20)).items())
                .isEmpty();
    }

    private String token() {
        return jwtService.createToken(TENANT_ID, "jdbc-admin", "JDBC 管理员",
                List.of("skill:write", "skill:read"));
    }

    private static MockMultipartFile skillZip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry("SKILL.md"));
            out.write("---\nname: rollback-skill\ndescription: 回滚测试\n---\n正文".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("references/guide.md"));
            out.write("资源".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return new MockMultipartFile("file", "rollback.zip", "application/zip", bytes.toByteArray());
    }
}
