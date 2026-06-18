package com.cmagent.starter;

import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.runtime.FakeAgentRuntime;
import com.cmagent.core.security.DefaultPermissionEvaluator;
import com.cmagent.core.security.DefaultToolAuthorizationPolicy;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.core.security.ToolAuthorizationPolicy;
import com.cmagent.core.tool.InMemoryToolRegistry;
import com.cmagent.core.tool.ToolRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CmAgentProperties.class)
public class CmAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AgentRuntime agentRuntime() {
        return new FakeAgentRuntime();
    }

    @Bean
    @ConditionalOnMissingBean
    PermissionEvaluator permissionEvaluator() {
        return new DefaultPermissionEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean
    ToolAuthorizationPolicy toolAuthorizationPolicy() {
        return new DefaultToolAuthorizationPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    ToolRegistry toolRegistry() {
        return new InMemoryToolRegistry();
    }
}
