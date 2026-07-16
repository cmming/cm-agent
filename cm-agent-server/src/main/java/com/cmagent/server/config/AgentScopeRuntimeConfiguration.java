package com.cmagent.server.config;

import com.cmagent.agentscope.AgentScopeRuntimeAdapter;
import com.cmagent.agentscope.AgentScopeRuntimeOptions;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.runtime.ModelCredentialProvider;
import com.cmagent.core.runtime.ToolInvocationGateway;
import com.cmagent.server.runtime.ExternalModelCredentialProvider;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Objects;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentScopeRuntimeProperties.class)
@ConditionalOnProperty(prefix = "cm-agent.agentscope", name = "enabled", havingValue = "true")
public class AgentScopeRuntimeConfiguration {

    @Bean
    InitializingBean agentScopeRuntimePropertiesValidator(
            AgentScopeRuntimeProperties properties,
            @Value("${cm-agent.fake-runtime-enabled:false}") boolean fakeRuntimeEnabled
    ) {
        return () -> properties.validate(fakeRuntimeEnabled);
    }

    @Bean
    @ConditionalOnMissingBean({AgentRuntime.class, ModelCredentialProvider.class})
    ModelCredentialProvider externalModelCredentialProvider(AgentScopeRuntimeProperties properties) {
        return new ExternalModelCredentialProvider(properties);
    }

    @Bean
    @ConditionalOnMissingBean(AgentRuntime.class)
    AgentRuntime agentScopeRuntime(
            AgentScopeRuntimeProperties properties,
            ObjectProvider<ModelCredentialProvider> credentialProvider,
            ToolInvocationGateway gateway
    ) {
        properties.validate(false);
        ModelCredentialProvider provider = Objects.requireNonNull(
                credentialProvider.getIfAvailable(),
                "启用 AgentScope runtime 时必须配置 ModelCredentialProvider");
        AgentScopeRuntimeOptions options = new AgentScopeRuntimeOptions(
                properties.getModelTimeout(), properties.getToolTimeout(), properties.getModelMaxAttempts());
        return AgentScopeRuntimeAdapter.create(provider, gateway, options, Clock.systemUTC());
    }
}
