package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.domain.SkillVersionView;
import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.runtime.RuntimeApprovalDecisions;
import com.cmagent.core.runtime.SkillAccessGateway;
import com.cmagent.core.runtime.SkillReadRequest;
import com.cmagent.core.runtime.SkillReadResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 专用浏览器 fixture 的快速契约测试；正式 Web、仓储与浏览器验收另行执行。 */
class ApprovalExpiryFlowTest {
    @Test
    void 专用启动配置强制测试回环和短周期且密钥每次随机生成() {
        String password = UUID.randomUUID().toString();
        var first = ApprovalExpiryBrowserFixture.safeProperties(password);
        var second = ApprovalExpiryBrowserFixture.safeProperties(password);
        assertThat(first).containsEntry("spring.profiles.active", "test")
                .containsEntry("server.address", "127.0.0.1")
                .containsEntry("server.port", "18093")
                .containsEntry("cm-agent.persistence.mode", "memory")
                .containsEntry("cm-agent.agentscope.enabled", "false")
                .containsEntry("cm-agent.agentscope.approval-ttl", "8s")
                .containsEntry("cm-agent.approval-expiry.interval", "1s")
                .containsEntry("cm-agent.security.allow-dev-jwt-fallback", "false");
        assertThat(first.get("cm-agent.security.jwt-secret")).isNotEqualTo(second.get("cm-agent.security.jwt-secret"));
        assertThatThrownBy(() -> ApprovalExpiryBrowserFixture.safeProperties(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalExpiryBrowserFixture.main(new String[]{"--server.address=0.0.0.0"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 未读取输入不生成技能读取记录() {
        SkillAccessGateway gateway = mock(SkillAccessGateway.class);
        AgentRunRequest request = request("不读取");
        var result = new ApprovalExpiryBrowserFixture.ControlledRuntime(gateway).run(request);
        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.output()).contains("不能作为发布依据");
        verifyNoInteractions(gateway);
    }

    @Test
    void 正常读取经治理网关使用原Run和固定版本而不是伪造发布资格() {
        SkillAccessGateway gateway = mock(SkillAccessGateway.class);
        AgentRunRequest request = request("读取技能");
        SkillVersionView view = mock(SkillVersionView.class);
        SkillDefinition definition = mock(SkillDefinition.class);
        SkillVersion version = mock(SkillVersion.class);
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(definition.id()).thenReturn(skillId);
        when(version.id()).thenReturn(versionId);
        when(version.content()).thenReturn("固定版本的技能正文");
        when(view.definition()).thenReturn(definition);
        when(view.version()).thenReturn(version);
        when(request.skills()).thenReturn(List.of(view));
        when(gateway.load(any(), any())).thenAnswer(invocation -> {
            Supplier<String> loader = invocation.getArgument(1);
            assertThat(loader.get()).isEqualTo("固定版本的技能正文");
            return new SkillReadResult(loader.get(), UUID.randomUUID());
        });
        var result = new ApprovalExpiryBrowserFixture.ControlledRuntime(gateway).run(request);
        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        ArgumentCaptor<SkillReadRequest> read = ArgumentCaptor.forClass(SkillReadRequest.class);
        verify(gateway).load(read.capture(), any());
        assertThat(read.getValue().runId()).isEqualTo(request.runId());
        assertThat(read.getValue().skillId()).isEqualTo(skillId);
        assertThat(read.getValue().versionId()).isEqualTo(versionId);
        assertThat(read.getValue().path()).isEqualTo("SKILL.md");
    }

    @Test
    void 审批只用已授权高风险工具且恢复保持原Run不执行外部调用() {
        SkillAccessGateway gateway = mock(SkillAccessGateway.class);
        AgentRunRequest request = request("审批");
        ToolDefinition tool = mock(ToolDefinition.class);
        UUID toolId = UUID.randomUUID();
        when(tool.id()).thenReturn(toolId);
        when(tool.name()).thenReturn("fixture_tool");
        when(tool.riskLevel()).thenReturn(ToolRiskLevel.HIGH);
        when(request.tools()).thenReturn(List.of(tool));
        when(request.skills()).thenReturn(List.of());
        var runtime = new ApprovalExpiryBrowserFixture.ControlledRuntime(gateway);
        var waiting = runtime.runStructured(request, ignored -> {}, ignored -> {});
        assertThat(waiting.run().status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(waiting.pendingApproval().items().getFirst().toolId()).isEqualTo(toolId);
        var decisions = new RuntimeApprovalDecisions(List.of(new RuntimeApprovalDecisions.Item(
                waiting.pendingApproval().items().getFirst().toolCallId(), toolId, "a".repeat(64), ToolApprovalDecision.APPROVE)));
        var resumed = runtime.resumeStructured(request, decisions, ignored -> {}, ignored -> {});
        assertThat(resumed.run().runId()).isEqualTo(waiting.run().runId());
        assertThat(resumed.run().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(resumed.run().toolCalls()).isEmpty();
        verifyNoInteractions(gateway);
    }

    private static AgentRunRequest request(String input) {
        AgentRunRequest request = mock(AgentRunRequest.class);
        when(request.runId()).thenReturn(UUID.randomUUID());
        when(request.input()).thenReturn(input);
        // mock 只服务于 fixture 的运行时契约测试，真实浏览器使用正式请求构造器及认证主体。
        UUID agentId = UUID.randomUUID();
        PrincipalRef principal = mock(PrincipalRef.class);
        when(request.agentId()).thenReturn(agentId);
        when(request.principal()).thenReturn(principal);
        return request;
    }
}
