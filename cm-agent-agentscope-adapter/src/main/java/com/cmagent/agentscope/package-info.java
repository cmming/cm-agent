/**
 * CM Agent 领域运行时与 AgentScope 2.0.0 之间的适配层。
 *
 * <p>{@link com.cmagent.agentscope.AgentScopeRuntimeAdapter} 实现 Core 的 {@code AgentRuntime}
 * 同步契约，内部通过 {@link com.cmagent.agentscope.AgentScopeReActExecutor} 驱动
 * {@code ReActAgent} 事件流，并把每次工具调用收敛到受治理的
 * {@code ToolInvocationGateway}。中间类型（{@link com.cmagent.agentscope.AgentScopeRunSpec}、
 * {@link com.cmagent.agentscope.AgentScopeExecutionResult}、
 * {@link com.cmagent.agentscope.AgentScopeRunGate}）刻意保持包内可见，
 * 避免其他模块耦合 AgentScope 类型。</p>
 *
 * <p>本包对 AgentScope 的扩展依赖（模型 Provider）保持 optional；只有
 * {@link com.cmagent.agentscope.AgentScopeModelFactory} 与
 * {@link com.cmagent.agentscope.AgentScopeReActExecutor} 直接引用框架类，
 * 引入方需自行保证具备相应实现模块。</p>
 */
package com.cmagent.agentscope;

