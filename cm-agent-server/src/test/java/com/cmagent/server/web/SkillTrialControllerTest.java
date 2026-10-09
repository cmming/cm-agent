package com.cmagent.server.web;

import com.cmagent.core.domain.SkillTrial;
import com.cmagent.core.domain.SkillTrialStatus;
import com.cmagent.core.domain.RunKind;
import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.RunToolCall;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.runtime.RunPersistenceService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class, properties = {
        "cm-agent.skills.enabled=true", "cm-agent.persistence.mode=memory"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillTrialControllerTest {
    @Test void 刷新只查询认证主体的最新版本试运行()throws Exception{
        when(trials.latest(any(),eq(SKILL_ID),eq(VERSION_ID))).thenReturn(java.util.Optional.empty());
        mockMvc.perform(get("/api/skills/"+SKILL_ID+"/trials/latest").param("versionId",VERSION_ID.toString())
            .header("Authorization","Bearer "+jwtService.createToken(TENANT_ID,"owner","创建者",List.of("skill:read","agent:read"))))
            .andExpect(status().isNoContent());
        verify(trials).latest(org.mockito.ArgumentMatchers.argThat(p->p.principalId().equals("owner")&&p.tenantId().equals(TENANT_ID)),eq(SKILL_ID),eq(VERSION_ID));
        mockMvc.perform(get("/api/skills/"+SKILL_ID+"/trials/latest").param("versionId",VERSION_ID.toString())
            .header("Authorization","Bearer "+jwtService.createToken(TENANT_ID,"owner","创建者",List.of("skill:read"))))
            .andExpect(status().isForbidden());
    }
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
        RunRecord run = new RunRecord(RUN_ID, TENANT_ID, AGENT_ID, "tester", RunKind.TEST, RunStatus.SUCCEEDED,
                "不得返回的运行输入", "技能运行结果", "", now, now);
        RunToolCall toolCall = new RunToolCall(UUID.randomUUID(), TENANT_ID, RUN_ID, UUID.randomUUID(),
                "查询工具", "{\"关键词\":\"已脱敏\"}", "找到两条结果", RunStatus.SUCCEEDED,
                true, 18L, "", now);
        when(trials.start(any(), eq(SKILL_ID), eq(VERSION_ID), eq(AGENT_ID), eq("测试技能试运行")))
                .thenReturn(trial);
        when(trials.get(any(), eq(RUN_ID))).thenReturn(trial);
        when(trials.runDetail(any(), eq(RUN_ID)))
                .thenReturn(new RunPersistenceService.RunDetail(run, List.of(toolCall)));

        mockMvc.perform(post("/api/skills/{skillId}/trials", SKILL_ID)
                        .header("Authorization", "Bearer " + jwtService.createToken(
                                TENANT_ID, "tester", "测试用户", List.of("skill:write", "agent:run", "agent:read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionId":"%s","agentId":"%s","input":"测试技能试运行"}
                                """.formatted(VERSION_ID, AGENT_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.status").value("NOT_TRIGGERED"))
                .andExpect(jsonPath("$.preview.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.preview.output").value("技能运行结果"))
                .andExpect(jsonPath("$.preview.toolCalls[0].toolName").value("查询工具"))
                .andExpect(jsonPath("$.preview.toolCalls[0].inputSummary").value("{\"关键词\":\"已脱敏\"}"))
                .andExpect(jsonPath("$.preview.input").doesNotExist())
                .andExpect(jsonPath("$.preview.tenantId").doesNotExist())
                .andExpect(jsonPath("$.preview.toolCalls[0].toolId").doesNotExist());

        verify(trials).execute(any(), eq(trial), eq("测试技能试运行"), any(), any());
    }

    @Test
    void 查询试运行结果预览需要Agent读取权限() throws Exception {
        Instant now = Instant.now();
        SkillTrial trial = new SkillTrial(RUN_ID, TENANT_ID, SKILL_ID, VERSION_ID, AGENT_ID, 0,
                SkillTrialStatus.PASSED, true, "tester", now, now);
        when(trials.get(any(), eq(RUN_ID))).thenReturn(trial);

        mockMvc.perform(get("/api/skills/{skillId}/trials/{runId}", SKILL_ID, RUN_ID)
                        .header("Authorization", "Bearer " + jwtService.createToken(
                                TENANT_ID, "tester", "测试用户", List.of("skill:read"))))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(trials, org.mockito.Mockito.never()).runDetail(any(), any());
    }

    @Test
    void 有权限时查询试运行详情返回结果预览() throws Exception {
        Instant now = Instant.now();
        SkillTrial trial = new SkillTrial(RUN_ID, TENANT_ID, SKILL_ID, VERSION_ID, AGENT_ID, 0,
                SkillTrialStatus.PASSED, true, "tester", now, now);
        RunRecord run = new RunRecord(RUN_ID, TENANT_ID, AGENT_ID, "tester", RunKind.TEST, RunStatus.SUCCEEDED,
                "不得返回的运行输入", "已恢复的试运行结果", "", now, now);
        when(trials.get(any(), eq(RUN_ID))).thenReturn(trial);
        when(trials.runDetail(any(), eq(RUN_ID)))
                .thenReturn(new RunPersistenceService.RunDetail(run, List.of()));

        mockMvc.perform(get("/api/skills/{skillId}/trials/{runId}", SKILL_ID, RUN_ID)
                        .header("Authorization", "Bearer " + jwtService.createToken(
                                TENANT_ID, "tester", "测试用户", List.of("skill:read", "agent:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.preview.output").value("已恢复的试运行结果"))
                .andExpect(jsonPath("$.preview.toolCalls").isEmpty())
                .andExpect(jsonPath("$.preview.input").doesNotExist());
    }
}
