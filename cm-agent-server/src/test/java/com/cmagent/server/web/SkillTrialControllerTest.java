package com.cmagent.server.web;

import com.cmagent.core.domain.SkillTrial;
import com.cmagent.core.domain.SkillTrialStatus;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.runtime.SkillTrialService;
import com.cmagent.server.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class, properties = {
        "cm-agent.skills.enabled=true", "cm-agent.persistence.mode=memory"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillTrialControllerTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SKILL_ID = UUID.randomUUID();
    private static final UUID VERSION_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockBean private SkillTrialService trials;

    @Test
    void 创建试运行时显式绑定skillId路径变量() throws Exception {
        Instant now = Instant.now();
        SkillTrial trial = new SkillTrial(RUN_ID, TENANT_ID, SKILL_ID, VERSION_ID, AGENT_ID, 0,
                SkillTrialStatus.NOT_TRIGGERED, false, "tester", now, now);
        when(trials.start(any(), eq(SKILL_ID), eq(VERSION_ID), eq(AGENT_ID), eq("测试技能试运行")))
                .thenReturn(trial);
        when(trials.get(any(), eq(RUN_ID))).thenReturn(trial);

        mockMvc.perform(post("/api/skills/{skillId}/trials", SKILL_ID)
                        .header("Authorization", "Bearer " + jwtService.createToken(
                                TENANT_ID, "tester", "测试用户", List.of("skill:write", "agent:run")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionId":"%s","agentId":"%s","input":"测试技能试运行"}
                                """.formatted(VERSION_ID, AGENT_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.status").value("NOT_TRIGGERED"));

        verify(trials).execute(any(), eq(trial), eq("测试技能试运行"), any(), any());
    }
}
