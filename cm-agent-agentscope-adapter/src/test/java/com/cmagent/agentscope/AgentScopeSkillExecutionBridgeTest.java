package com.cmagent.agentscope;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.SkillResource;
import com.cmagent.core.domain.SkillVersionView;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.core.runtime.SkillAccessGateway;
import com.cmagent.core.runtime.SkillReadRequest;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentScopeSkillExecutionBridgeTest {
    @Test
    void 默认网关不公开任何代码执行工具() {
        Toolkit toolkit = new Toolkit();
        new AgentScopeSkillSession(request(), toolkit, new AgentScopeRunGate(), (request, loader) -> null);
        assertThat(toolkit.getTool(AgentScopeSkillExecutionBridge.TOOL_NAME)).isNull();
        assertThat(toolkit.getTool("run_skill_code")).isNull();
    }

    @Test
    void 执行时只将可信运行上下文和固定脚本交给网关() {
        Toolkit toolkit = new Toolkit();
        SkillAccessGateway gateway = mock(SkillAccessGateway.class);
        when(gateway.executionEnabled()).thenReturn(true);
        when(gateway.execute(any(), eq("输入"))).thenAnswer(call -> {
            SkillReadRequest trusted = call.getArgument(0);
            assertThat(trusted.principal()).isEqualTo(request().principal());
            assertThat(trusted.agentId()).isEqualTo(request().agentId());
            assertThat(trusted.runId()).isEqualTo(request().runId());
            assertThat(trusted.versionId()).isEqualTo(request().skills().getFirst().version().id());
            assertThat(trusted.path()).isEqualTo("scripts/main.py");
            assertThat(trusted.modelCallId()).isEqualTo("script-call");
            return "脚本结果";
        });
        new AgentScopeSkillSession(request(), toolkit, new AgentScopeRunGate(), gateway);
        var result = toolkit.getTool(AgentScopeSkillExecutionBridge.TOOL_NAME).callAsync(param("scripts/main.py")).block();
        assertThat(result.getOutput()).singleElement().isInstanceOfSatisfying(TextBlock.class,
                block -> assertThat(block.getText()).isEqualTo("脚本结果"));
        verify(gateway).execute(any(), eq("输入"));
        verify(gateway, never()).load(any(), any());
    }

    @Test
    void 任意宿主路径和未登记脚本均不调用网关() {
        SkillAccessGateway gateway = mock(SkillAccessGateway.class);
        var bridge = new AgentScopeSkillExecutionBridge(request(), new AgentScopeRunGate(), gateway,
                Map.of(nativeId(), request().skills().getFirst()));
        bridge.callAsync(param("/etc/passwd.py")).block();
        bridge.callAsync(param("../main.py")).block();
        bridge.callAsync(param("scripts/unknown.py")).block();
        verifyNoInteractions(gateway);
    }

    @Test
    void 致命沙箱失败进入共享门控并阻止后续执行() {
        SkillAccessGateway gateway = mock(SkillAccessGateway.class);
        when(gateway.execute(any(), any())).thenAnswer(call -> {
            SkillReadRequest trusted = call.getArgument(0);
            throw new SkillAccessException(ApiErrorCode.SKILL_SANDBOX_TIMEOUT, "技能沙箱执行超时", trusted.attemptId().toString(), true);
        });
        AgentScopeRunGate gate = new AgentScopeRunGate();
        var bridge = new AgentScopeSkillExecutionBridge(request(), gate, gateway, Map.of(nativeId(), request().skills().getFirst()));
        assertThatThrownBy(() -> bridge.callAsync(param("scripts/main.py")).block()).isInstanceOf(SkillAccessException.class);
        assertThatThrownBy(gate::throwIfSkillFailure).isInstanceOf(SkillAccessException.class);
        assertThatThrownBy(() -> bridge.callAsync(param("scripts/main.py")).block()).isInstanceOf(SkillAccessException.class);
        verify(gateway, times(1)).execute(any(), any());
        var toolGateway = mock(com.cmagent.core.runtime.ToolInvocationGateway.class);
        assertThatThrownBy(() -> gate.invoke(toolGateway, null)).isInstanceOf(SkillAccessException.class);
        verifyNoInteractions(toolGateway);
    }

    static AgentRunRequest request() {
        AgentRunRequest original = AgentScopeSkillSessionTest.request();
        SkillVersionView skill = original.skills().getFirst();
        SkillResource script = new SkillResource(skill.definition().tenantId(), skill.definition().id(), skill.version().id(),
                "scripts/main.py", "text/plain", "print(1)", 8, "a".repeat(64));
        return new AgentRunRequest(original.runId(), original.tenantId(), original.agent(), original.modelConfig(),
                original.principal(), original.input(), original.tools(), original.conversationId(),
                List.of(new SkillVersionView(skill.definition(), skill.version(), List.of(script))));
    }

    private static String nativeId() {
        return new AgentScopeSkillRepository(request().skills()).getSkill("deploy-guide").getSkillId();
    }

    private static ToolCallParam param(String path) {
        Map<String, Object> input = Map.of("skillId", nativeId(), "path", path, "stdin", "输入");
        return ToolCallParam.builder().toolUseBlock(new ToolUseBlock("script-call", AgentScopeSkillExecutionBridge.TOOL_NAME, input))
                .input(input).build();
    }
}
