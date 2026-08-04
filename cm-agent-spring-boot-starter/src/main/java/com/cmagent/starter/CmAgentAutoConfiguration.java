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
/**
 * CM Agent Starter 的默认 Bean 装配入口。
 *
 * <p>所有 Bean 都允许业务应用通过声明同类型 Bean 进行替换。</p>
 */
public class CmAgentAutoConfiguration {

    /**
     * 在显式启用 fake runtime 且应用未提供运行时时创建本地模拟实现。
     *
     * @return 不访问外部模型的模拟 Agent 运行时
     */
    @Bean
    @ConditionalOnProperty(prefix = "cm-agent", name = "fake-runtime-enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    AgentRuntime agentRuntime() {
        return new FakeAgentRuntime();
    }

    /**
     * 创建基于主体权限集合的默认权限判断器。
     *
     * @return 默认权限判断器
     */
    @Bean
    @ConditionalOnMissingBean
    PermissionEvaluator permissionEvaluator() {
        return new DefaultPermissionEvaluator();
    }

    /**
     * 创建同时检查权限和工具风险等级的默认授权策略。
     *
     * @return 默认工具授权策略
     */
    @Bean
    @ConditionalOnMissingBean
    ToolAuthorizationPolicy toolAuthorizationPolicy() {
        return new DefaultToolAuthorizationPolicy();
    }

    /**
     * 创建进程内工具执行器注册表，供本地工具注册和查找使用。
     *
     * @return 内存工具注册表
     */
    @Bean
    @ConditionalOnMissingBean
    ToolRegistry toolRegistry() {
        return new InMemoryToolRegistry();
    }
}
