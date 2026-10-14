package com.cmagent.agentscope;

import com.cmagent.core.runtime.*;
import com.cmagent.core.domain.AgentTextDelta;
import com.cmagent.core.domain.AgentProgressEvent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 受控 Harness 策略：复用现有权限、Skill、事件映射与状态清理，只替换框架入口。
 *
 * <p>每次运行/恢复创建新包装和私有目录；审批状态仍在宿主 Store，关闭不删除待审批状态。
 * 2.0.2 的 Harness 不提供 interrupt(context)，中断使用当前包装的 delegate。</p>
 */
final class AgentScopeHarnessExecutor implements AgentScopeExecutor {
    @Override public boolean supportsWorkspaceContext() { return true; }
    /** 唯一治理执行循环，避免两个策略各自维护审批与严格失败映射。 */
    private final AgentScopeReActExecutor pipeline;

    AgentScopeHarnessExecutor(AgentScopeRuntimeOptions options, AgentStateStore store, SkillAccessGateway skills) {
        pipeline = new AgentScopeReActExecutor(options, new AgentScopeModelFactory(), store, new HarnessLifecycle(), skills);
    }
    @Override public boolean supportsCancellation() { return true; }
    @Override public AgentScopeExecutionResult execute(AgentScopeRunSpec s, ModelCredential c, ToolInvocationGateway g) {
        return pipeline.execute(s,c,g);
    }
    @Override public AgentScopeExecutionResult execute(AgentScopeRunSpec s, ModelCredential c, ToolInvocationGateway g, Consumer<String> d) {
        return pipeline.execute(s,c,g,d);
    }
    @Override public AgentScopeExecutionResult executeStructured(AgentScopeRunSpec s, ModelCredential c, ToolInvocationGateway g,
            Consumer<AgentTextDelta> d, Consumer<AgentProgressEvent> p) {
        return pipeline.executeStructured(s,c,g,d,p);
    }
    @Override public AgentScopeExecutionResult resumeStructured(AgentScopeRunSpec s, ModelCredential c, ToolInvocationGateway g,
            RuntimeApprovalDecisions a, Consumer<AgentTextDelta> d, Consumer<AgentProgressEvent> p) {
        return pipeline.resumeStructured(s,c,g,a,d,p);
    }

    /** 实例共享仅用于关联活跃 delegate 与资源；关闭立即移除，不能缓存跨 Run 的 Toolkit/凭据。 */
    static class HarnessLifecycle implements AgentScopeReActExecutor.AgentLifecycle {
        /** 活跃实例由各运行持有，线程安全映射仅服务事件入口及 finally 关闭。 */
        private final Map<ReActAgent, OwnedHarness> live = new ConcurrentHashMap<>();

        @Override public ReActAgent create(ReActAgent.Builder builder) {
            ReActAgent base = builder.build();
            AgentScopeHarnessIsolation isolation = null;
            HarnessAgent harness = null;
            try {
                isolation = new AgentScopeHarnessIsolation();
                harness = build(base, isolation);
                ReActAgent delegate = harness.getDelegate();
                live.put(delegate, new OwnedHarness(base, harness, isolation));
                return delegate;
            } catch (RuntimeException | Error failure) {
                // 创建半途失败也必须关闭所有已创建资源；清理错误附加根因，不能覆盖安全失败。
                if (harness != null) try { harness.close(); } catch (RuntimeException e) { failure.addSuppressed(e); }
                try { base.close(); } catch (RuntimeException e) { failure.addSuppressed(e); }
                if (isolation != null) try { isolation.close(); } catch (RuntimeException e) { failure.addSuppressed(e); }
                throw failure;
            }
        }

        /** 在模型可见之前核对最终工具全集及实例身份，不允许框架默认工具或别名混入。 */
        static HarnessAgent build(ReActAgent base, AgentScopeHarnessIsolation isolation) {
            var originals = new LinkedHashMap<String, io.agentscope.core.tool.AgentTool>();
            for (var name : base.getToolkit().getToolNames()) {
                var tool = base.getToolkit().getTool(name);
                if (!(tool instanceof AgentScopeToolBridge || tool instanceof AgentScopeSkillLoadBridge
                        || tool instanceof AgentScopeSkillExecutionBridge))
                    throw new IllegalStateException("Harness 工具必须使用 CM Agent 治理桥接器");
                originals.put(name, tool);
            }
            var harness = HarnessAgent.Builder.fromAgent(base)
                    .workspace(isolation.workspace()).abstractFilesystem(isolation.filesystem())
                    .messageBus(isolation.bus()).asyncToolRegistry(isolation.registry())
                    .disableFilesystemTools().disableShellTool().disableMemoryTools().disableMemoryHooks()
                    .disableSessionPersistence().disableWorkspaceContext().disableAtPathExpansion()
                    .disableSubagents().disableDynamicSubagents().disableToolsConfig()
                    .disableDynamicSkills().disableDefaultWorkspaceSkills().skillsEnabled(false)
                    .disableCompaction().disableToolResultEviction().enableAgentTracingLog(false)
                    .enableMetaTool(false).enableTaskList(false).enablePlanMode(false).build();
            try {
                for (var name : List.copyOf(harness.getToolkit().getToolNames())) {
                    if (!originals.containsKey(name)) harness.getToolkit().removeTool(name);
                }
                if (!Set.copyOf(harness.getToolkit().getToolNames()).equals(originals.keySet())
                        || originals.entrySet().stream().anyMatch(e -> harness.getToolkit().getTool(e.getKey()) != e.getValue())
                        || !harness.getSkillRepositories().isEmpty())
                    throw new IllegalStateException("Harness 最终工具白名单校验失败");
                isolation.check();
                return harness;
            } catch (RuntimeException | Error failure) {
                try { harness.close(); } catch (RuntimeException close) { failure.addSuppressed(close); }
                throw failure;
            }
        }
        @Override public Flux<AgentEvent> streamEvents(ReActAgent agent, Msg msg, RuntimeContext context) {
            var owned = Objects.requireNonNull(live.get(agent), "Harness 生命周期未登记");
            return Flux.defer(() -> {
                owned.isolation().check();
                return owned.harness().streamEvents(msg, context)
                        .doOnNext(event -> owned.isolation().check()).doOnComplete(owned.isolation()::check);
            });
        }
        @Override public void interrupt(ReActAgent agent, RuntimeContext context) { agent.interrupt(context); }
        @Override public void close(ReActAgent agent) {
            var owned = live.remove(agent);
            if (owned == null) return;
            RuntimeException failure = null;
            try { owned.harness().close(); } catch (RuntimeException e) { failure = e; }
            try { owned.base().close(); } catch (RuntimeException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            try { owned.isolation().close(); } catch (RuntimeException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            if (failure != null) throw failure;
        }
    }

    /**
     * 一次创建的独占资源。
     * @param base 配置源 Agent，包装关闭后另行关闭
     * @param harness 真正的事件入口及 delegate 持有者
     * @param isolation 私有目录与拒绝型宿主能力，最后清理
     */
    private record OwnedHarness(ReActAgent base, HarnessAgent harness, AgentScopeHarnessIsolation isolation) {}
}

