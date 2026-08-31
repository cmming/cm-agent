package com.cmagent.server.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * AgentScope Studio 的开发期条件化装配。
 *
 * <p>初始化成功后，AgentScope 会把 Studio 消息 Hook 作为系统 Hook 应用于后续创建的 Agent，故无需
 * 修改运行执行器或在每次 {@code ReActAgent} 构造时重复注册。严格生产 profile 不创建此配置，
 * 并由 {@code ProfileSafetyValidator} 对误开开关实施启动失败保护。</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("!production & !prod & !supabase")
@ConditionalOnProperty(prefix = "cm-agent.agentscope.studio", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AgentScopeStudioProperties.class)
public class AgentScopeStudioConfiguration {

    /**
     * 创建默认 Studio 静态管理器适配器；测试或宿主可替换该 Bean 以隔离外部连接。
     *
     * @return AgentScope Studio 初始化器
     */
    @Bean
    @ConditionalOnMissingBean(AgentScopeStudioRuntime.class)
    AgentScopeStudioRuntime agentScopeStudioRuntime() {
        return new AgentScopeStudioManagerRuntime();
    }

    /**
     * 在 Spring 启动阶段建立 Studio HTTP/WebSocket 连接并注册系统 Hook。
     *
     * @param properties Studio 配置属性
     * @param runtime Studio 初始化边界
     * @return Spring 初始化回调
     */
    @Bean
    InitializingBean agentScopeStudioInitializer(
            AgentScopeStudioProperties properties,
            AgentScopeStudioRuntime runtime
    ) {
        return () -> {
            // 配置必须在调用可替换的运行时边界前完成校验，避免测试替身或宿主实现绕过安全约束。
            properties.validate();
            runtime.initialize(properties);
        };
    }
}
