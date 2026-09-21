package com.cmagent.agentscope;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillResource;
import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.domain.SkillVersionView;
import com.cmagent.core.runtime.SkillReadResult;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证运行技能会话只使用固定内存快照，并把正文读取交给治理网关。 */
class AgentScopeSkillSessionTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final UUID SKILL_ID = UUID.fromString("00000000-0000-0000-0000-000000000105");
    private static final UUID VERSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000106");
    private static final String SHA256 = "a".repeat(64);

    @Test
    void repositoryIsReadOnlyAndDoesNotAssignOriginDirectory() {
        AgentScopeSkillRepository repository = new AgentScopeSkillRepository(List.of(skill()));

        assertThat(repository.getAllSkillNames()).containsExactly("deploy-guide");
        assertThat(repository.getSkill("deploy-guide").getOriginDir()).isEmpty();
        assertThat(repository.isWriteable()).isFalse();
        assertThatThrownBy(() -> repository.setWriteable(true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.save(List.of(), false))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.delete("deploy-guide"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void loadsOnlyKnownSnapshotPathThroughGatewayAndKeepsBodyOutOfDirectoryPrompt() {
        AtomicReference<com.cmagent.core.runtime.SkillReadRequest> captured = new AtomicReference<>();
        Toolkit toolkit = new Toolkit();
        AgentScopeSkillSession session = new AgentScopeSkillSession(
                request(), toolkit, new AgentScopeRunGate(), (readRequest, nativeLoader) -> {
                    captured.set(readRequest);
                    return new SkillReadResult(nativeLoader.get(), UUID.randomUUID());
                });

        assertThat(session.directoryPrompt())
                .contains("deploy-guide")
                .contains("发布操作说明")
                .doesNotContain("仅限本轮加载的正文");

        ToolResultBlock result = toolkit.getTool(AgentScopeSkillLoadBridge.TOOL_NAME).callAsync(
                ToolCallParam.builder()
                        .toolUseBlock(new ToolUseBlock("call-1", AgentScopeSkillLoadBridge.TOOL_NAME,
                                Map.of("skillId", nativeSkillId(), "path", "SKILL.md")))
                        .input(Map.of("skillId", nativeSkillId(), "path", "SKILL.md"))
                        .build())
                .block();

        assertThat(result).isNotNull();
        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(result.getOutput()).singleElement().isInstanceOfSatisfying(TextBlock.class,
                block -> assertThat(block.getText()).contains("仅限本轮加载的正文"));
        assertThat(captured.get()).satisfies(read -> {
            assertThat(read.agentId()).isEqualTo(AGENT_ID);
            assertThat(read.runId()).isEqualTo(RUN_ID);
            assertThat(read.skillId()).isEqualTo(SKILL_ID);
            assertThat(read.versionId()).isEqualTo(VERSION_ID);
            assertThat(read.path()).isEqualTo("SKILL.md");
            assertThat(read.modelCallId()).isEqualTo("call-1");
        });
    }

    @Test
    void rejectsUnknownSkillBeforeCallingGateway() {
        Toolkit toolkit = new Toolkit();
        AgentScopeSkillSession session = new AgentScopeSkillSession(
                request(), toolkit, new AgentScopeRunGate(), (readRequest, nativeLoader) -> {
                    throw new AssertionError("未知技能不能进入读取网关");
                });

        ToolResultBlock result = toolkit.getTool(AgentScopeSkillLoadBridge.TOOL_NAME).callAsync(
                ToolCallParam.builder()
                        .toolUseBlock(new ToolUseBlock("call-2", AgentScopeSkillLoadBridge.TOOL_NAME,
                                Map.of("skillId", "other", "path", "SKILL.md")))
                        .input(Map.of("skillId", "other", "path", "SKILL.md"))
                        .build())
                .block();

        assertThat(session.directoryPrompt()).contains("deploy-guide");
        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(result.getOutput()).singleElement().isInstanceOfSatisfying(TextBlock.class,
                block -> assertThat(block.getText()).contains("请求的技能资源不存在"));
    }

    static AgentRunRequest request() {
        AgentDefinition agent = new AgentDefinition(AGENT_ID, TENANT_ID, "测试 Agent", "", "测试系统提示",
                MODEL_ID, "test-model", 0.2, 3, true, List.of(), "tester", "tester");
        ModelConfig model = new ModelConfig(MODEL_ID, TENANT_ID, ModelProviderType.OPENAI_COMPATIBLE,
                "测试模型", "https://example.test/v1", "test-model", true);
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "tester", "测试主体", Set.of("agent:run"));
        return new AgentRunRequest(RUN_ID, TENANT_ID, agent, model, principal, "测试", List.of(), null,
                List.of(skill()));
    }

    static SkillVersionView skill() {
        Instant createdAt = Instant.parse("2026-09-20T00:00:00Z");
        SkillDefinition definition = new SkillDefinition(SKILL_ID, TENANT_ID, "deploy-guide", VERSION_ID,
                true, 0, "tester", "tester", createdAt, createdAt);
        SkillVersion version = new SkillVersion(VERSION_ID, TENANT_ID, SKILL_ID, 1, "发布操作说明", Map.of(),
                "仅限本轮加载的正文", SHA256, "tester", createdAt);
        SkillResource resource = new SkillResource(TENANT_ID, SKILL_ID, VERSION_ID, "references/check.md",
                "text/markdown", "检查清单", "检查清单".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                SHA256);
        return new SkillVersionView(definition, version, List.of(resource));
    }

    private static String nativeSkillId() {
        return new AgentScopeSkillRepository(List.of(skill())).getSkill("deploy-guide").getSkillId();
    }
}
