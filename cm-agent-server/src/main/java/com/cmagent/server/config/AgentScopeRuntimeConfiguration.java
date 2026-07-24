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
/** AgentScope 真实运行时的条件化装配，凭据由外部提供者按租户隔离。 */
public class AgentScopeRuntimeConfiguration {

    /**
     * 创建 AgentScope 运行时配置校验回调。
     *
     * @param properties         AgentScope 配置属性
     * @param fakeRuntimeEnabled 是否启用 fake runtime
     * @return Spring 初始化回调
     * @throws IllegalStateException 配置冲突或参数不合法时抛出
     */
    @Bean
    InitializingBean agentScopeRuntimePropertiesValidator(
            AgentScopeRuntimeProperties properties,
            @Value("${cm-agent.fake-runtime-enabled:false}") boolean fakeRuntimeEnabled
    ) {
        return () -> properties.validate(fakeRuntimeEnabled);
    }

    /**
     * 创建按租户和模型配置解析凭据的默认提供者。
     *
     * @param properties AgentScope 配置属性
     * @return 外部模型凭据提供者
     * @throws IllegalStateException 未配置任何模型凭据且未提供自定义提供者时抛出
     */
    @Bean
    @ConditionalOnMissingBean({AgentRuntime.class, ModelCredentialProvider.class})
    ModelCredentialProvider externalModelCredentialProvider(AgentScopeRuntimeProperties properties) {
        if (properties.getCredentials().isEmpty()) {
            throw new IllegalStateException(
                    "启用 AgentScope runtime 时必须配置模型凭据或自定义 ModelCredentialProvider");
        }
        return new ExternalModelCredentialProvider(properties);
    }

    /**
     * 创建 AgentScope 真实运行时，并接入受治理的工具调用网关。
     *
     * @param properties         AgentScope 运行时配置
     * @param credentialProvider Spring 管理的模型凭据提供者
     * @param gateway            工具调用治理网关
     * @return AgentScope AgentRuntime
     * @throws IllegalStateException 未找到模型凭据提供者或运行时配置不合法时抛出
     */
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
