package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.server.config.LocalProjectProperties;
import com.cmagent.server.runtime.*;
import com.cmagent.server.security.JwtService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = LocalProjectControllerTest.Application.class, properties = {
        "spring.flyway.enabled=false", "spring.main.banner-mode=off"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LocalProjectControllerTest {
    @Autowired MockMvc mvc;
    @Autowired LocalProjectService service;
    @Autowired LocalProjectSelections selections;
    @Autowired LocalProjectAccessService access;
    @Autowired LocalProjectRequestBoundary boundary;
    @Autowired LocalProjectProperties policy;
    @Autowired LocalProjectArchiveService archives;
    @Autowired LocalProjectTools projectTools;
    private final UUID tenant = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach void setup() {
        reset(service, selections, access, boundary, archives, projectTools); policy.setEnabled(true);
        when(boundary.permits(any())).thenReturn(true);
        when(access.reject(any(), any(), anyString(), anyInt(), anyString())).thenAnswer(call ->
                new LocalProjectAccessException(call.getArgument(2), "safe-error-id", call.getArgument(3), call.getArgument(4)));
    }

    @Test void 未认证请求不能启动原生选择而本机来源失败在服务查询之前拒绝() throws Exception {
        mvc.perform(post("/api/projects/selections").contentType("application/json").content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(selections);
        when(boundary.permits(any())).thenReturn(false);
        mvc.perform(get("/api/projects").with(auth()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("PROJECT_LOCAL_REQUEST_REQUIRED"))
                .andExpect(jsonPath("$.errorId").value("safe-error-id"));
        verifyNoInteractions(service);
    }

    @Test void 仅使用Jwt主体且正文响应禁止缓存() throws Exception {
        var text = new LocalProjectService.Text("src/Test.java", "0".repeat(64), 8, 0, 8, false, false, "安全正文");
        when(service.read(any(), eq(projectId), eq("src/Test.java"), eq(0), eq(65536))).thenReturn(text);
        mvc.perform(get("/api/projects/{id}/text", projectId).param("path", "src/Test.java").with(auth()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.content").value("安全正文"));
        verify(service).read(argThat(principal -> tenant.equals(principal.tenantId()) && "Owner".equals(principal.principalId())), eq(projectId), anyString(), eq(0), eq(65536));
    }

    @Test void 业务失败沿用编号与HTTP状态且关闭能力不要求原生组件() throws Exception {
        when(service.get(any(), eq(projectId))).thenThrow(new LocalProjectAccessException("PROJECT_NOT_FOUND", "same-id", 404, "项目不存在或不可访问"));
        mvc.perform(get("/api/projects/{id}", projectId).with(auth()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("PROJECT_NOT_FOUND"))
                .andExpect(jsonPath("$.errorId").value("same-id"));
        policy.setEnabled(false);
        mvc.perform(get("/api/projects/capabilities").with(auth())).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.commands").value(false));
        mvc.perform(get("/api/projects").with(auth())).andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor auth() {
        return authentication(new UsernamePasswordAuthenticationToken(new JwtService.JwtSession(tenant, "Owner", "测试", List.of("project:read")), null, List.of()));
    }
    @Test void 归档路由只传JWT主体和版本且本机边界与关闭模式均先拒绝() throws Exception {
        var now = java.time.Instant.now();
        when(archives.archive(any(), eq(projectId), eq(3L))).thenReturn(new LocalProjectService.View(projectId, "测试项目", "ARCHIVED", 4, now, now));
        mvc.perform(post("/api/projects/{id}/archive", projectId).with(auth()).contentType("application/json").content("{\"expectedVersion\":3}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.state").value("ARCHIVED"));
        verify(archives).archive(argThat(principal -> principal.tenantId().equals(tenant) && principal.principalId().equals("Owner")), eq(projectId), eq(3L));
        reset(archives); when(boundary.permits(any())).thenReturn(false);
        mvc.perform(post("/api/projects/{id}/archive", projectId).with(auth()).contentType("application/json").content("{\"expectedVersion\":3}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorId").value("safe-error-id"));
        policy.setEnabled(false);
        mvc.perform(post("/api/projects/{id}/archive", projectId).with(auth()).contentType("application/json").content("{\"expectedVersion\":3}"))
                .andExpect(status().isNotFound()); verifyNoInteractions(archives);
    }

    @Test void 归档冲突保持具体原因编号且无效版本不进入服务() throws Exception {
        when(archives.archive(any(), eq(projectId), eq(3L))).thenThrow(new LocalProjectAccessException("PROJECT_ACTIVE_RUNS", "same-archive-id", 409, "项目仍有待审批任务，请先停止"));
        mvc.perform(post("/api/projects/{id}/archive", projectId).with(auth()).contentType("application/json").content("{\"expectedVersion\":3}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PROJECT_ACTIVE_RUNS"))
                .andExpect(jsonPath("$.errorId").value("same-archive-id")).andExpect(jsonPath("$.message").value("项目仍有待审批任务，请先停止"));
        reset(archives);
        mvc.perform(post("/api/projects/{id}/archive", projectId).with(auth()).contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest()); verifyNoInteractions(archives);
    }

    @Test void 受控预览到期保留410与同号中文提示而不包装成基础设施失败() throws Exception {
        UUID run=UUID.randomUUID(),operation=UUID.randomUUID();
        var expired=new LocalProjectAccessException("PROJECT_OPERATION_EXPIRED","same-expired-id",410,"操作清单已超过保留期限，请重新生成提案；历史状态仍可查看");
        when(projectTools.commandPreview(any(),eq(projectId),eq(run),eq(operation))).thenThrow(expired);
        when(projectTools.changesPreview(any(),eq(projectId),eq(run),eq(operation))).thenThrow(expired);
        for(String kind:List.of("commands","changes"))mvc.perform(get("/api/projects/{id}/runs/{run}/{kind}/{operation}",projectId,run,kind,operation)
                .param("preview","true").with(auth()))
                .andExpect(status().isGone()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.errorCode").value("PROJECT_OPERATION_EXPIRED"))
                .andExpect(jsonPath("$.errorId").value("same-expired-id"))
                .andExpect(jsonPath("$.message").value(expired.getMessage()));
        verifyNoInteractions(access);
    }

    /** 最小Web集成上下文不启用本机项目装配、不读取凭据或连接数据库；原生服务替身只验证路由边界。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class})
    @Import(LocalProjectController.class)
    static class Application {
        @Bean LocalProjectService service() { return mock(LocalProjectService.class); }
        @Bean LocalProjectSelections selections() { return mock(LocalProjectSelections.class); }
        @Bean LocalProjectAccessService access() { return mock(LocalProjectAccessService.class); }
        @Bean LocalProjectRequestBoundary boundary() { return mock(LocalProjectRequestBoundary.class); }
        @Bean LocalProjectProperties policy() { return new LocalProjectProperties(); }
        @Bean LocalProjectArchiveService archives() { return mock(LocalProjectArchiveService.class); }
        @Bean LocalProjectTools projectTools() { return mock(LocalProjectTools.class); }
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated()).build();
        }
    }
}
