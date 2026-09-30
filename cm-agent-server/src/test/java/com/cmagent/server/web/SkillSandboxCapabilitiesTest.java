package com.cmagent.server.web;

import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = CmAgentServerApplication.class, properties = {
        "cm-agent.skills.enabled=true", "cm-agent.skills.sandbox.enabled=true", "cm-agent.persistence.mode=memory"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SkillSandboxCapabilitiesTest {
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;

    @Test
    void 显式启用时能力与Python导入白名单一致() throws Exception {
        String token = jwt.createToken(UUID.fromString("00000000-0000-0000-0000-000000000001"), "sandbox-tester",
                "沙箱验证", List.of("skill:read", "skill:write"));
        mvc.perform(get("/api/skills/capabilities").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sandboxEnabled").value(true))
                .andExpect(jsonPath("$.scriptLanguages[0]").value("python"))
                .andExpect(jsonPath("$.allowedExtensions", org.hamcrest.Matchers.hasItem(".py")))
                .andExpect(jsonPath("$.image").doesNotExist())
                .andExpect(jsonPath("$.runtime").doesNotExist());
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write("---\nname: python-skill\ndescription: Python 沙箱验证\n---\n执行 scripts/main.py".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("scripts/main.py"));
            zip.write("print(1)".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        mvc.perform(multipart("/api/skills").file(new MockMultipartFile("file", "skill.zip", "application/zip", bytes.toByteArray()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resources[0].path").value("scripts/main.py"));
    }
}
