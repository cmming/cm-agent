package com.cmagent.server.web;

import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.LocalProjectProperties;
import com.cmagent.server.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=LocalProjectBootstrapLoginTest.Application.class,properties={
        "spring.flyway.enabled=false","spring.main.banner-mode=off",
        // ConfigurationProperties会在@Bean返回后重新绑定profile值，合成凭据必须在最高优先级测试属性中固定。
        "cm-agent.security.bootstrap-admin-enabled=true",
        "cm-agent.security.bootstrap-admin-username=project-test-owner",
        "cm-agent.security.bootstrap-admin-password=synthetic-project-login-password"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LocalProjectBootstrapLoginTest {
    @Autowired MockMvc mvc;
    @Autowired LocalProjectProperties projects;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;

    @BeforeEach void resetPolicy(){
        projects.setEnabled(false);projects.setBootstrapPermissions(List.of());projects.setNodeId("test-node");
        projects.setStateDirectory(Path.of(System.getProperty("java.io.tmpdir"),"local-project-auth-test").toAbsolutePath().toString());
        projects.setAllowedOrigins(List.of("http://127.0.0.1:19080"));
    }

    @Test void 默认空配置不自动扩大正常登录权限()throws Exception{
        projects.setEnabled(true);
        var response=json.readTree(login());
        assertThat(response.path("permissions").toString()).doesNotContain("project:","tool:write");
        assertThat(jwt.parseAndVerify(response.path("accessToken").asText()).permissions()).doesNotContain("project:read");
    }

    @Test void 关闭功能时显式项目权限仍不进入真实JWT()throws Exception{
        projects.setBootstrapPermissions(List.of("project:read","project:manage"));
        var response=json.readTree(login());
        assertThat(jwt.parseAndVerify(response.path("accessToken").asText()).permissions()).doesNotContain("project:read","project:manage");
    }

    @Test void 显式四项授权通过真实登录与验证后的当前用户API可见()throws Exception{
        projects.setEnabled(true);projects.setBootstrapPermissions(List.of("project:read","project:write","project:execute","project:manage"));
        var response=json.readTree(login());String token=response.path("accessToken").asText();
        assertThat(jwt.parseAndVerify(token).permissions()).contains("project:read","project:write","project:execute","project:manage","tool:grant").doesNotContain("tool:write");
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions",org.hamcrest.Matchers.hasItems("project:read","project:write","project:execute","project:manage")))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test void 非白名单或重复权限在启用校验时失败关闭(){
        projects.setEnabled(true);
        for(var invalid:List.of(List.of("project:*"),List.of("tool:write"),List.of("project:read","agent:delete"),List.of("project:read","project:read"))){
            projects.setBootstrapPermissions(invalid);
            assertThatThrownBy(projects::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("四项明确项目权限");
        }
    }

    @Test void 显式本机权限仍不能在生产混合或缺失本机profile时装配(){
        projects.setEnabled(true);projects.setBootstrapPermissions(List.of("project:read"));
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of("projects",projects));
        for(String[] profiles:List.of(new String[]{"prod"},new String[]{"test","production"},new String[]{})){
            var environment=new org.springframework.mock.env.MockEnvironment();environment.setActiveProfiles(profiles);
            var controller=new AuthController(jwt,new BootstrapAdminProperties(environment),mock(AuditAppender.class));
            assertThatThrownBy(()->controller.configureLocalProjects(beans.getBeanProvider(LocalProjectProperties.class),environment))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("local/test");
        }
    }

    private String login()throws Exception{
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"project-test-owner\",\"password\":\"synthetic-project-login-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** 仅认证链集成：真实登录/签名/过滤器，不装配原生目录或JDBC，也不使用真实部署凭据。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class})
    @Import(AuthController.class)
    static class Application {
        @Bean LocalProjectProperties projects(){return new LocalProjectProperties();}
        @Bean AuditAppender audit(){return mock(AuditAppender.class);}
        @Bean JwtService jwt()throws Exception{var generator=KeyGenerator.getInstance("HmacSHA256");return new JwtService(generator.generateKey());}
        @Bean BootstrapAdminProperties bootstrap(Environment environment){
            var properties=new BootstrapAdminProperties(environment);properties.setBootstrapAdminEnabled(true);
            properties.setBootstrapAdminUsername("project-test-owner");properties.setBootstrapAdminPassword("synthetic-project-login-password");return properties;
        }
        @Bean SecurityFilterChain security(HttpSecurity http,JwtService jwt)throws Exception{
            return http.csrf(csrf->csrf.disable()).authorizeHttpRequests(auth->auth.requestMatchers("/api/auth/login").permitAll().anyRequest().authenticated())
                    .addFilterBefore(new JwtAuthenticationFilter(jwt),UsernamePasswordAuthenticationFilter.class).build();
        }
    }
}
