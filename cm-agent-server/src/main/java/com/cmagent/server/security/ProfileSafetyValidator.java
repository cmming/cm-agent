package com.cmagent.server.security;

import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.runtime.FakeAgentRuntime;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

@Component
/** 启动时校验 profile 与安全配置组合，阻止生产环境误启用开发能力。 */
public class ProfileSafetyValidator implements InitializingBean {
    private static final Set<String> STRICT_PROFILES = Set.of("production", "prod", "supabase");
    private static final Set<String> NON_PRODUCTION_PROFILES = Set.of("local", "test", "postgres", "mysql");

    private final Environment environment;
    private final String persistenceMode;
    private final boolean bootstrapAdminEnabled;
    private final boolean devJwtFallbackEnabled;
    private final boolean fakeRuntimeEnabled;
    private final boolean agentScopeRuntimeEnabled;
    private final boolean httpAllowed;
    private final ObjectProvider<AgentRuntime> agentRuntimeProvider;

    public ProfileSafetyValidator(
            Environment environment,
            @Value("${cm-agent.persistence.mode:memory}") String persistenceMode,
            @Value("${cm-agent.security.bootstrap-admin-enabled:false}") boolean bootstrapAdminEnabled,
            @Value("${cm-agent.security.allow-dev-jwt-fallback:false}") boolean devJwtFallbackEnabled,
            @Value("${cm-agent.fake-runtime-enabled:false}") boolean fakeRuntimeEnabled,
            @Value("${cm-agent.agentscope.enabled:false}") boolean agentScopeRuntimeEnabled,
            @Value("${cm-agent.http-tools.allow-http:false}") boolean httpAllowed,
            ObjectProvider<AgentRuntime> agentRuntimeProvider
    ) {
        this.environment = environment;
        this.persistenceMode = persistenceMode;
        this.bootstrapAdminEnabled = bootstrapAdminEnabled;
        this.devJwtFallbackEnabled = devJwtFallbackEnabled;
        this.fakeRuntimeEnabled = fakeRuntimeEnabled;
        this.agentScopeRuntimeEnabled = agentScopeRuntimeEnabled;
        this.httpAllowed = httpAllowed;
        this.agentRuntimeProvider = agentRuntimeProvider;
    }

    @Override
    public void afterPropertiesSet() {
        Set<String> activeProfiles = Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (activeProfiles.isEmpty()) {
            throw new IllegalStateException("必须显式配置 spring.profiles.active 或 CM_AGENT_PROFILE");
        }
        boolean strictProfileActive = activeProfiles.stream().anyMatch(STRICT_PROFILES::contains);
        if (!strictProfileActive) {
            if (fakeRuntimeEnabled && agentScopeRuntimeEnabled) {
                throw new IllegalStateException("AgentScope 真实运行时与 fake runtime 不能同时启用");
            }
            return;
        }
        if (activeProfiles.stream().anyMatch(NON_PRODUCTION_PROFILES::contains)) {
            throw new IllegalStateException("production/prod/supabase profile 禁止与 local/test/postgres/mysql profile 同时启用");
        }
        if (httpAllowed) {
            throw new IllegalStateException("production/prod/supabase profile 禁止启用 HTTP 明文协议");
        }
        if (!"jdbc".equalsIgnoreCase(persistenceMode)) {
            throw new IllegalStateException("production/prod/supabase profile 必须使用 jdbc 持久化模式");
        }
        if (bootstrapAdminEnabled) {
            throw new IllegalStateException("production/prod/supabase profile 禁止启用 bootstrap admin");
        }
        if (devJwtFallbackEnabled) {
            throw new IllegalStateException("production/prod/supabase profile 禁止启用开发 JWT 回退");
        }
        if (fakeRuntimeEnabled) {
            throw new IllegalStateException("production/prod/supabase profile 禁止启用 fake runtime");
        }
        AgentRuntime agentRuntime = agentRuntimeProvider.getIfAvailable();
        if (agentRuntime == null || agentRuntime instanceof FakeAgentRuntime) {
            throw new IllegalStateException("production/prod/supabase profile 必须提供真实 AgentRuntime");
        }
    }
}
