package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 验证技能会话不会覆盖业务工具，并保持无技能运行的既有工具集。 */
class AgentScopeSkillContractTest {

    @Test
    void rejectsBusinessToolThatUsesReservedNativeSkillLoadName() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(reservedBusinessTool());

        assertThatIllegalArgumentException().isThrownBy(() -> new AgentScopeSkillSession(
                        AgentScopeSkillSessionTest.request(), toolkit, new AgentScopeRunGate(),
                        (request, nativeLoader) -> {
                            throw new AssertionError("冲突时不能读取技能");
                        }))
                .withMessage("业务工具名称不能使用 load_skill_through_path");
    }

    @Test
    void noSkillRunDoesNotRegisterNativeLoadTool() {
        AgentRunRequest populated = AgentScopeSkillSessionTest.request();
        AgentRunRequest withoutSkills = new AgentRunRequest(
                populated.runId(), populated.tenantId(), populated.agent(), populated.modelConfig(),
                populated.principal(), populated.input(), List.of(), populated.conversationId(), List.of());
        Toolkit toolkit = new Toolkit();

        AgentScopeSkillSession session = new AgentScopeSkillSession(
                withoutSkills, toolkit, new AgentScopeRunGate(), (request, nativeLoader) -> {
                    throw new AssertionError("无技能运行不能读取技能");
                });

        assertThat(session.directoryPrompt()).isEmpty();
        assertThat(toolkit.getTool(AgentScopeSkillLoadBridge.TOOL_NAME)).isNull();
    }

    private static AgentTool reservedBusinessTool() {
        return new AgentTool() {
            @Override
            public String getName() {
                return AgentScopeSkillLoadBridge.TOOL_NAME;
            }

            @Override
            public String getDescription() {
                return "冲突业务工具";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object");
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("不应被调用"));
            }
        };
    }
}
