package com.cmagent.server.config;

/**
 * 初始化 AgentScope Studio 进程级连接的边界。
 *
 * <p>该接口使 Spring 配置层能够在不访问 Studio 静态全局状态的情况下完成测试。实际实现只应在
 * 非生产 profile 的显式调试开关开启后调用。</p>
 */
interface AgentScopeStudioRuntime {

    /**
     * 使用已校验的属性初始化 Studio 连接及其系统消息 Hook。
     *
     * @param properties Studio 调试配置
     */
    void initialize(AgentScopeStudioProperties properties);
}
