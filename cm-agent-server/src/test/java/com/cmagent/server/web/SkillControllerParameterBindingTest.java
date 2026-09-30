package com.cmagent.server.web;

import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.domain.SkillBindingMode;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillPreflightCheck;
import com.cmagent.core.domain.SkillPreflightScope;
import com.cmagent.core.domain.SkillPreflightStatus;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillDependencyMappingRepository;
import com.cmagent.core.repository.SkillDependencyRepository;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.service.SkillDependencyMappingService;
import com.cmagent.server.service.SkillManagementService;
import com.cmagent.server.service.SkillPreflightResult;
import com.cmagent.server.service.SkillPreflightService;
import com.cmagent.server.service.SkillQueryService;
import com.cmagent.server.service.SkillReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 技能发布治理 HTTP 参数回归；明确剥离编译器参数名，防止只在 IDE 编译配置下通过。 */
@SpringBootTest(classes = CmAgentServerApplication.class, properties = {
        "cm-agent.skills.enabled=true", "cm-agent.persistence.mode=memory", "cm-agent.approval-expiry.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillControllerParameterBindingTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SKILL = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID PREVIOUS = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private RequestMappingHandlerAdapter adapter;
    @MockBean private SkillReleaseService releases;
    @MockBean private SkillManagementService management;
    @MockBean private SkillQueryService queries;
    @MockBean private SkillDependencyMappingService mappingService;
    @MockBean private SkillPreflightService preflightService;
    @MockBean private SkillDefinitionRepository definitions;
    @MockBean private SkillDependencyRepository dependencies;
    @MockBean private SkillDependencyMappingRepository mappings;

    @BeforeEach
    void 禁用反射参数名发现() {
        // 即使未来构建开启 -parameters，本组回归仍强制 MVC 只使用注解协议名，不借用编译器元数据。
        adapter.setParameterNameDiscoverer(new ParameterNameDiscoverer() {
            @Override public String[] getParameterNames(Method method) { return null; }
            @Override public String[] getParameterNames(Constructor<?> constructor) { return null; }
        });
    }

    @Test
    void 发布在没有参数名元数据时进入真实认证与业务入口() throws Exception {
        mvc.perform(post("/api/skills/{skillId}/releases", SKILL)
                        .header("Authorization", token("skill:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"candidateVersionId":"%s","expectedPublishedVersionId":null,"qualifyingTrialRunId":"%s"}
                                """.formatted(VERSION, RUN)))
                .andExpect(status().isCreated());
        verify(releases).publish(any(), eq(SKILL), eq(VERSION), isNull(), eq(RUN));
    }

    @Test
    void 回滚在没有参数名元数据时正确传递版本与发布条件() throws Exception {
        mvc.perform(post("/api/skills/{skillId}/rollbacks", SKILL)
                        .header("Authorization", token("skill:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetVersionId":"%s","expectedPublishedVersionId":"%s"}
                                """.formatted(VERSION, PREVIOUS)))
                .andExpect(status().isCreated());
        verify(releases).rollback(any(), eq(SKILL), eq(VERSION), eq(PREVIOUS));
    }

    @Test
    void 依赖映射的技能与逻辑键均从显式路径名解析() throws Exception {
        UUID tool = UUID.randomUUID();
        Instant now = Instant.now();
        SkillDefinition definition = new SkillDefinition(SKILL, TENANT, "fixture-skill", VERSION,
                true, 0, "tester", "tester", now, now);
        when(definitions.find(TENANT, SKILL)).thenReturn(Optional.of(definition));
        when(dependencies.list(TENANT, SKILL, VERSION)).thenReturn(List.of());
        when(mappings.list(TENANT, SKILL)).thenReturn(List.of());
        mvc.perform(put("/api/skills/{id}/dependencies/{logicalKey}", SKILL, "required-check")
                        .header("Authorization", token("skill:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toolId":"%s","expectedRevision":3}
                                """.formatted(tool)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillId").value(SKILL.toString()));
        verify(mappingService).map(any(), eq(SKILL), eq("required-check"), eq(tool), eq(3L));
    }

    @Test
    void 预检查询参数在无元数据时仍按协议名称解析() throws Exception {
        SkillPreflightCheck check = new SkillPreflightCheck(UUID.randomUUID(), TENANT, SKILL, VERSION, 2,
                SkillPreflightScope.AGENT, AGENT, SkillPreflightStatus.PASSED, "tester", Instant.now());
        when(preflightService.preflight(any(), eq(SKILL), eq(VERSION), eq(SkillPreflightScope.AGENT), eq(AGENT)))
                .thenReturn(new SkillPreflightResult(check, List.of()));
        mvc.perform(post("/api/skills/{id}/preflights", SKILL)
                        .header("Authorization", token("skill:write"))
                        .param("versionId", VERSION.toString()).param("scope", "AGENT").param("agentId", AGENT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.check.agentId").value(AGENT.toString()));
        verify(preflightService).preflight(any(), eq(SKILL), eq(VERSION), eq(SkillPreflightScope.AGENT), eq(AGENT));
    }

    @Test
    void 绑定策略在无元数据时正确解析Agent技能与修订() throws Exception {
        AgentSkillBinding binding = new AgentSkillBinding(UUID.randomUUID(), TENANT, AGENT, SKILL,
                SkillBindingMode.PINNED, VERSION, 4, "tester", "tester", Instant.now(), Instant.now());
        when(releases.bindOrUpdate(any(), eq(AGENT), eq(SKILL), eq(SkillBindingMode.PINNED), eq(VERSION), eq(3L)))
                .thenReturn(binding);
        when(queries.bindings(any(), eq(AGENT))).thenReturn(List.of(new SkillResponses.Binding(binding.id(),
                SKILL, "fixture-skill", "受控技能", 1, true, "PINNED", VERSION, 4)));
        mvc.perform(put("/api/agents/{agentId}/skills/{skillId}", AGENT, SKILL)
                        .header("Authorization", token("agent:write", "skill:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PINNED","pinnedVersionId":"%s","expectedRevision":3}
                                """.formatted(VERSION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("PINNED"));
        verify(releases).bindOrUpdate(any(), eq(AGENT), eq(SKILL), eq(SkillBindingMode.PINNED), eq(VERSION), eq(3L));
    }

    @Test
    void 显式参数绑定保留认证与发布权限拒绝() throws Exception {
        String body = """
                {"candidateVersionId":"%s","qualifyingTrialRunId":"%s"}
                """.formatted(VERSION, RUN);
        mvc.perform(post("/api/skills/{skillId}/releases", SKILL)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/skills/{skillId}/releases", SKILL)
                        .header("Authorization", token("skill:read"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    private String token(String... permissions) {
        return "Bearer " + jwt.createToken(TENANT, "tester", "测试用户", List.of(permissions));
    }
}
