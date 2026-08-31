package com.cmagent.server.config;

import io.agentscope.core.studio.StudioConfig;
import io.agentscope.core.studio.StudioManager;

import java.util.Objects;

/**
 * 使用 AgentScope {@link StudioManager} 初始化开发期 Studio 调试连接。
 *
 * <p>AgentScope 2.0.2 在 {@code StudioManager.init().initialize()} 过程中注册全局
 * {@code StudioMessageHook}，因此这里不能在每个请求中重新初始化或变更 Run 名称；否则并发运行
 * 会把消息推送到错误的 Studio Run，且重复注册 Hook。</p>
 */
final class AgentScopeStudioManagerRuntime implements AgentScopeStudioRuntime {

    @Override
    public synchronized void initialize(AgentScopeStudioProperties properties) {
        properties.validate();
        if (StudioManager.isInitialized()) {
            verifyExistingConfiguration(properties, StudioManager.getConfig());
            return;
        }
        StudioManager.init()
                .studioUrl(properties.getUrl())
                .project(properties.getProject())
                .runName(properties.getRunName())
                .initialize()
                .block();
    }

    /**
     * 防止同一 JVM 中其他初始化器悄然覆盖当前实例应使用的 Studio 目标。
     *
     * @param properties 当前 Spring 配置
     * @param existing 已存在的 AgentScope 全局配置
     */
    private static void verifyExistingConfiguration(AgentScopeStudioProperties properties, StudioConfig existing) {
        if (existing == null || !Objects.equals(properties.getUrl(), existing.getStudioUrl())
                || !Objects.equals(properties.getProject(), existing.getProject())
                || !Objects.equals(properties.getRunName(), existing.getRunName())) {
            throw new IllegalStateException("当前 JVM 已使用不同配置初始化 AgentScope Studio，不能重复覆盖全局 Studio 连接");
        }
    }
}
