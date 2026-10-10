package com.cmagent.server.runtime;

import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.*;
import com.cmagent.server.CmAgentServerApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * 聊天停止浏览器专用验收入口：回环 18094、test profile、随机密钥和 memory，拒绝参数覆盖。
 * 密码仅从环境变量提供；调用方负责停止进程。真实 REST/SSE、JWT、审批和审计保持原样，
 * 受控 Runtime 不联网、不执行外部工具，不能代替真实 Provider 或双库验收。
 */
public final class ChatStopBrowserFixture {
    private ChatStopBrowserFixture() { }

    /**
     * 启动仅限本机验收的服务；安全参数固定，凭据只从调用方的临时环境读取。
     * @param args 必须为空，不允许通过命令行覆盖 profile、端口或安全默认值
     */
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("停止验收不允许覆盖安全配置");
        var settings = ApprovalExpiryBrowserFixture.safeProperties(System.getenv("CM_AGENT_FIXTURE_PASSWORD"));
        settings.put("server.port", "18094");
        settings.put("management.server.port", "18094");
        settings.put("cm-agent.approval-expiry.enabled", "false");
        settings.put("cm-agent.agentscope.approval-ttl", "10m");
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("chat-stop-fixture", settings));
        environment.setActiveProfiles("test");
        var application = new SpringApplication(CmAgentServerApplication.class, Configuration.class);
        application.setEnvironment(environment);
        application.run();
    }

    /** 只被 main 显式加载，不进入普通生产组件扫描。 */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {
        @Bean @Primary AgentRuntime controlledRuntime() { return new ControlledRuntime(); }
        /** 仅创建隔离内存验收数据；端点是占位符且 Runtime 从不访问。 */
        @Bean ApplicationRunner seed(ModelConfigRepository models, AgentDefinitionRepository agents,
                ToolDefinitionRepository tools, ToolGrantRepository grants) {
            return ignored -> {
                UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000001");
                var model = models.save(new ModelConfig(UUID.randomUUID(), tenant, ModelProviderType.OPENAI_COMPATIBLE,
                        "受控停止验收模型", "https://example.invalid/v1", "controlled", true));
                var tool = tools.save(new ToolDefinition(UUID.randomUUID(), tenant, "controlled_high", "受控审批，不执行工具",
                        ToolType.LOCAL, "{}", ToolRiskLevel.HIGH, true, "", "fixture-admin", "fixture-admin"));
                var agent = agents.save(new AgentDefinition(UUID.randomUUID(), tenant, "聊天停止验收", "受控 Runtime",
                        "", model.id(), "controlled", 0.1, 5, true, List.of(tool.id()), "fixture-admin", "fixture-admin"));
                grants.save(new ToolGrant(tenant, tool.id(), agent.id(), "", true));
            };
        }
    }

    /** 协作信号控制受限等待；模拟工具持有许可，停止后延迟释放以观察停止中状态。 */
    static final class ControlledRuntime implements AgentRuntime {
        @Override public boolean supportsCancellation() { return true; }
        @Override public AgentRunResult run(AgentRunRequest request) { throw new UnsupportedOperationException("仅使用结构化入口"); }
        @Override public AgentRuntimeResult runStructured(AgentRunRequest request, Consumer<AgentTextDelta> delta, Consumer<AgentProgressEvent> progress) {
            if (request.input().contains("快速结束")) return complete(request, "新一轮已经正常完成。");
            if (request.input().contains("等待审批")) {
                var tool = request.tools().stream().filter(item -> item.riskLevel() == ToolRiskLevel.HIGH).findFirst().orElseThrow();
                var pending = new RuntimePendingApproval("fixture-reply", List.of(new RuntimePendingApproval.RuntimePendingToolCall(
                        "fixture-call", tool.id(), tool.name(), ToolRiskLevel.HIGH, "仅受控验收，不执行外部工具", "a".repeat(64))));
                return new AgentRuntimeResult(new AgentRunResult(request.runId(), RunStatus.WAITING_APPROVAL, "", List.of(),
                        Instant.now(), null, ""), null, pending);
            }
            return waitForStop(request, delta);
        }
        @Override public AgentRuntimeResult resumeStructured(AgentRunRequest request, RuntimeApprovalDecisions decisions,
                Consumer<AgentTextDelta> delta, Consumer<AgentProgressEvent> progress) {
            return waitForStop(request, delta);
        }
        private AgentRuntimeResult waitForStop(AgentRunRequest request, Consumer<AgentTextDelta> delta) {
            delta.accept(new AgentTextDelta("fixture-reply", "text", "这是停止前已经输出的正文。\n\n"));
            try (var permit = request.cancellation().enter()) {
                request.cancellation().signal().get(120, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(1500);
            } catch (Exception failure) {
                throw new IllegalStateException("受控等待未完成", failure);
            }
            return complete(request, "正文已收口");
        }
        private AgentRuntimeResult complete(AgentRunRequest request, String output) {
            var now = Instant.now();
            return new AgentRuntimeResult(new AgentRunResult(request.runId(), RunStatus.SUCCEEDED, output, List.of(), now, now, ""),
                    new AgentMessageSnapshot("fixture-reply", request.agent().name(), List.of(MessageContentBlock.text(output))));
        }
    }
}
