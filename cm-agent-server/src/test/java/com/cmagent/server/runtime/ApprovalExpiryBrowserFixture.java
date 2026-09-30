package com.cmagent.server.runtime;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.AgentRuntimeResult;
import com.cmagent.core.domain.AgentTextDelta;
import com.cmagent.core.domain.AgentProgressEvent;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.RuntimePendingApproval;
import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.runtime.RuntimeApprovalDecisions;
import com.cmagent.core.runtime.SkillAccessGateway;
import com.cmagent.core.runtime.SkillReadRequest;
import com.cmagent.server.CmAgentServerApplication;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 主动过期及技能发布流程的专用浏览器验收入口，不属于生产启动方式。
 *
 * <ol>
 * <li>显式运行本类的 {@code main}，密码从 {@code CM_AGENT_FIXTURE_PASSWORD} 提供。</li>
 * <li>在回环端口 18093 使用真实登录、JWT、memory 仓储和服务层导入隔离资源。</li>
 * <li>受控 Runtime 经真实技能网关读取固定版本后，由正式试运行服务判定 PASSED。</li>
 * <li>输入含“审批”时等待已授权 HIGH 工具审批；批准后同一 Run 读取技能，过期由扫描器收口。</li>
 * </ol>
 *
 * <p>此 fixture 不执行真实模型或工具，不证明模型自动选择能力或外部副作用语义。
 * TestConfiguration 不被普通组件扫描发现；JUnit 不调用 main，不会留下常驻服务。
 * 进程及其资源由启动验收的调用方负责停止，所有内存数据随进程销毁。</p>
 */
public final class ApprovalExpiryBrowserFixture {
    private ApprovalExpiryBrowserFixture() {
    }

    /**
     * 只使用固定测试配置启动，拒绝命令行覆盖安全边界。
     *
     * @param args 必须为空；专用密码通过环境变量或 {@code cmagent.fixture.password} 系统属性提供
     */
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("验收 fixture 不允许命令行覆盖配置");
        String password = System.getProperty("cmagent.fixture.password", System.getenv("CM_AGENT_FIXTURE_PASSWORD"));
        StandardEnvironment environment = new StandardEnvironment();
        // 优先级高于已有 YAML 与系统变量，防止共享工作区配置将专用 fixture 暴露到外网或真实依赖。
        environment.getPropertySources().addFirst(new MapPropertySource("approval-browser-fixture", safeProperties(password)));
        environment.setActiveProfiles("test");
        SpringApplication application = new SpringApplication(CmAgentServerApplication.class, FixtureConfiguration.class);
        application.setEnvironment(environment);
        application.run();
    }

    /** 生成进程独享密钥，禁止把密码、JWT 或加密密钥输出到日志或固定测试文件。 */
    static Map<String, Object> safeProperties(String password) {
        if (password == null || password.length() < 12) {
            throw new IllegalArgumentException("必须提供至少 12 位的专用验收密码");
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.profiles.active", "test");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", "18093");
        // 管理端点复用主服务的回环地址；Spring Boot 不允许同端口单独指定管理地址。
        properties.put("management.server.port", "18093");
        properties.put("cm-agent.persistence.mode", "memory");
        properties.put("cm-agent.skills.enabled", "true");
        properties.put("cm-agent.agentscope.enabled", "false");
        properties.put("cm-agent.fake-runtime-enabled", "false");
        properties.put("cm-agent.agentscope.studio.enabled", "false");
        properties.put("cm-agent.agentscope.permission-enabled", "true");
        properties.put("cm-agent.agentscope.approval-ttl", "8s");
        properties.put("cm-agent.approval-expiry.enabled", "true");
        properties.put("cm-agent.approval-expiry.interval", "1s");
        properties.put("cm-agent.http-tools.allow-http", "false");
        // 仅让必需依赖预检识别完整 HTTP 定义；受控 Runtime 从不调用执行器，且禁止明文 HTTP。
        properties.put("cm-agent.http-tools.enabled", "true");
        properties.put("cm-agent.security.allow-dev-jwt-fallback", "false");
        properties.put("cm-agent.security.bootstrap-admin-enabled", "true");
        properties.put("cm-agent.security.bootstrap-admin-username", "fixture-admin");
        properties.put("cm-agent.security.bootstrap-admin-password", password);
        properties.put("cm-agent.security.bootstrap-admin-display-name", "受控验收管理员");
        byte[] key = new byte[48];
        new SecureRandom().nextBytes(key);
        properties.put("cm-agent.security.jwt-secret", Base64.getEncoder().encodeToString(key));
        byte[] encryptionKey = new byte[32];
        new SecureRandom().nextBytes(encryptionKey);
        properties.put("cm-agent.model-credentials.encryption-key", Base64.getEncoder().encodeToString(encryptionKey));
        return properties;
    }

    /** 仅显式导入时装配受控 Runtime，不能作为生产组件扫描入口。 */
    @TestConfiguration(proxyBeanMethods = false)
    public static class FixtureConfiguration {
        /** 再次检查实际环境，拒绝把 fixture 与生产 profile 或非回环服务器混用。 */
        @Bean
        InitializingBean fixtureSafetyBoundary(Environment environment) {
            return () -> {
                if (!Arrays.equals(environment.getActiveProfiles(), new String[]{"test"})
                        || !"127.0.0.1".equals(environment.getProperty("server.address"))
                        || !"memory".equals(environment.getProperty("cm-agent.persistence.mode"))
                        || environment.getProperty("cm-agent.agentscope.enabled", Boolean.class, false)) {
                    throw new IllegalStateException("浏览器 fixture 仅允许 test profile、回环地址、memory 与受控 Runtime");
                }
            };
        }

        /** 使用真实技能治理网关，不替换权限、快照、审计或发布门禁。 */
        @Bean
        @Primary
        AgentRuntime fixtureRuntime(SkillAccessGateway gateway) {
            return new ControlledRuntime(gateway);
        }
    }

    /**
     * 可控运行时只发出审批快照和技能读取，不调用任何模型或工具执行器。
     * PASSED 必须由正式服务依据实际读取记录判定，禁止由 fixture 直接写发布资格。
     */
    static final class ControlledRuntime implements AgentRuntime {
        /** Spring 注入的真实治理网关，本类只持有引用，不创建或关闭其资源。 */
        private final SkillAccessGateway gateway;

        ControlledRuntime(SkillAccessGateway gateway) {
            this.gateway = gateway;
        }

        @Override
        /** 成功状态只表示受控 Runtime 完成，是否读取目标技能由正式服务依据网关记录判定。 */
        public AgentRunResult run(AgentRunRequest request) {
            return complete(request, !request.input().contains("不读取"));
        }

        @Override
        /** 输入含“审批”时模拟授权 HIGH 工具的 ASK，其余输入执行受治理技能读取。 */
        public AgentRuntimeResult runStructured(AgentRunRequest request, Consumer<AgentTextDelta> delta,
                Consumer<AgentProgressEvent> progress) {
            if (!request.input().contains("审批")) return new AgentRuntimeResult(run(request), null);
            // 工具集合来自服务端实时授权筛选，禁止从输入伪造工具标识；这里仅模拟 ASK，不执行工具。
            var highTool = request.tools().stream().filter(tool -> tool.riskLevel() == ToolRiskLevel.HIGH)
                    .findFirst().orElseThrow(() -> new IllegalStateException("受控审批验收需要已授权 HIGH 工具"));
            var pending = new RuntimePendingApproval("fixture-reply-" + request.runId(), List.of(
                    new RuntimePendingApproval.RuntimePendingToolCall("fixture-call-" + request.runId(),
                            highTool.id(), highTool.name(), highTool.riskLevel(), "受控验收，不调用外部工具", "a".repeat(64))));
            var result = new AgentRunResult(request.runId(), RunStatus.WAITING_APPROVAL, "", List.of(),
                    Instant.now(), null, "");
            return new AgentRuntimeResult(result, null, pending);
        }

        @Override
        /** 只接受服务端重建的原 Run 与允许决定，读取其固定快照，不重新运行模型或工具。 */
        public AgentRuntimeResult resumeStructured(AgentRunRequest request, RuntimeApprovalDecisions decisions,
                Consumer<AgentTextDelta> delta, Consumer<AgentProgressEvent> progress) {
            // 上层已复核原发起人、授权及技能快照；fixture 仍要求允许项对应当前授权工具，且绝不发起外部调用。
            boolean allowed = decisions.items().stream().anyMatch(item -> item.decision() == ToolApprovalDecision.APPROVE
                    && request.tools().stream().anyMatch(tool -> tool.id().equals(item.toolId())));
            if (!allowed) throw new IllegalStateException("审批没有当前授权的允许项");
            return new AgentRuntimeResult(complete(request, true), null);
        }

        private AgentRunResult complete(AgentRunRequest request, boolean read) {
            Instant now = Instant.now();
            if (read) {
                for (var skill : request.skills()) {
                    gateway.load(new SkillReadRequest(request.principal(), request.agentId(), request.runId(),
                            "fixture-load-" + request.runId() + "-" + skill.definition().id(), UUID.randomUUID(),
                            skill.definition().id(), skill.version().id(), "SKILL.md"), skill.version()::content);
                }
            }
            return new AgentRunResult(request.runId(), RunStatus.SUCCEEDED,
                    read ? "受控验收：已通过治理网关读取固定技能版本，未调用真实模型或外部工具。" : "受控验收：未读取技能，不能作为发布依据。",
                    List.of(), now, Instant.now(), "");
        }
    }
}
