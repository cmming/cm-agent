package com.cmagent.starter;

import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.runtime.FakeAgentRuntime;
import com.cmagent.core.security.DefaultPermissionEvaluator;
import com.cmagent.core.security.DefaultToolAuthorizationPolicy;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.core.security.ToolAuthorizationPolicy;
import com.cmagent.core.tool.InMemoryToolRegistry;
import com.cmagent.core.tool.ToolRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(CmAgentProperties.class)
public class CmAgentAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "cm-agent", name = "fake-runtime-enabled", havingValue = "true", matchIfMissing = true)
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
