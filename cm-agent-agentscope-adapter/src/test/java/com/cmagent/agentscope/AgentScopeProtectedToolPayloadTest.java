package com.cmagent.agentscope;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class AgentScopeProtectedToolPayloadTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID MODEL = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID TOOL = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID CONVERSATION = UUID.fromString("00000000-0000-0000-0000-000000000701");

    @Test void 模型获得正文而记录进度和日志仅有安全摘要(CapturedOutput logs) {
        var events = new CopyOnWriteArrayList<AgentProgressEvent>();
        var input = new CopyOnWriteArrayList<ToolInvocationRequest>();
        var request = request(true, true, "read_owned_file");
        var bridge = new AgentScopeToolBridge(request, request.tools().getFirst(), value -> {
            input.add(value);
            return ToolInvocationResult.succeededProtected("PRIVATE_FILE_BODY", "文件读取完成，1项");
        }, new ObjectMapper(), new AgentScopeRunGate(), events::add);
        var result = bridge.callAsync(call("read_owned_file", "PRIVATE_PATCH_INPUT")).block();
        assertThat(modelText(result)).isEqualTo("PRIVATE_FILE_BODY");
        assertThat(input.getFirst().inputJson()).contains("PRIVATE_PATCH_INPUT");
        assertThat(bridge.records().getFirst().inputSummary()).isEqualTo(AgentScopeToolBridge.PROTECTED_INPUT_SUMMARY);
        assertThat(bridge.records().getFirst().outputSummary()).isEqualTo("文件读取完成，1项");
        assertThat(events.toString()).doesNotContain("PRIVATE_FILE_BODY", "PRIVATE_PATCH_INPUT");
        assertThat(bridge.records().toString()).doesNotContain("PRIVATE_FILE_BODY", "PRIVATE_PATCH_INPUT");
        assertThat(logs).doesNotContain("PRIVATE_FILE_BODY", "PRIVATE_PATCH_INPUT");
    }

    @Test void 旧网关结果在可信受保护工具上不回退正文摘要(CapturedOutput logs) {
        var events = new CopyOnWriteArrayList<AgentProgressEvent>();
        var request = request(true, true, "any_name");
        var bridge = new AgentScopeToolBridge(request, request.tools().getFirst(),
                value -> ToolInvocationResult.succeeded("PRIVATE_LEGACY_BODY"),
                new ObjectMapper(), new AgentScopeRunGate(), events::add);
        var result = bridge.callAsync(call("any_name", "PRIVATE_INPUT")).block();
        assertThat(modelText(result)).isEqualTo("PRIVATE_LEGACY_BODY");
        assertThat(bridge.records().getFirst().outputSummary()).isEqualTo(AgentScopeToolBridge.PROTECTED_OUTPUT_SUMMARY);
        assertThat(events.toString()).doesNotContain("PRIVATE_LEGACY_BODY", "PRIVATE_INPUT");
        assertThat(logs).doesNotContain("PRIVATE_LEGACY_BODY", "PRIVATE_INPUT");
    }

    @Test void 保护依据宿主精确ID而不是模型名称前缀() {
        var request = request(true, false, "project_read_file");
        var bridge = new AgentScopeToolBridge(request, request.tools().getFirst(),
                value -> ToolInvocationResult.succeeded("普通输出"), new ObjectMapper());
        bridge.callAsync(call("project_read_file", "普通输入")).block();
        assertThat(bridge.records().getFirst().inputSummary()).contains("普通输入");
        assertThat(bridge.records().getFirst().outputSummary()).isEqualTo("普通输出");
    }

    @Test void 非项目工具也可显式采用输出分离合同() {
        var request = request(false, false, "ordinary");
        var bridge = new AgentScopeToolBridge(request, request.tools().getFirst(),
                value -> ToolInvocationResult.succeededProtected("PRIVATE_OUTPUT", "安全摘要"), new ObjectMapper());
        assertThat(modelText(bridge.callAsync(call("ordinary", "参数")).block())).isEqualTo("PRIVATE_OUTPUT");
        assertThat(bridge.records().getFirst().outputSummary()).isEqualTo("安全摘要");
    }

    @Test void 受保护失败兼容降级不将错误夹带正文写入记录或日志(CapturedOutput logs) {
        var request = request(true, true, "protected");
        var bridge = new AgentScopeToolBridge(request, request.tools().getFirst(),
                value -> ToolInvocationResult.failed("PRIVATE_ERROR_BODY"), new ObjectMapper());
        assertThat(modelText(bridge.callAsync(call("protected", "PRIVATE_INPUT")).block())).contains("PRIVATE_ERROR_BODY");
        assertThat(bridge.records().getFirst().errorMessage()).isEqualTo(AgentScopeToolBridge.PROTECTED_FAILURE_SUMMARY);
        assertThat(bridge.records().toString()).doesNotContain("PRIVATE_ERROR_BODY", "PRIVATE_INPUT");
        assertThat(logs).doesNotContain("PRIVATE_ERROR_BODY", "PRIVATE_INPUT");
    }

    @Test void 可信网关的受保护拒绝保留可行动原因并隐藏输入(CapturedOutput logs) {
        var request = request(true, true, "protected");
        var events = new CopyOnWriteArrayList<AgentProgressEvent>();
        var bridge = new AgentScopeToolBridge(request, request.tools().getFirst(),
                value -> ToolInvocationResult.deniedProtected("项目授权已撤销，请重新绑定"),
                new ObjectMapper(), new AgentScopeRunGate(), events::add);
        assertThat(modelText(bridge.callAsync(call("protected", "PRIVATE_INPUT")).block()))
                .contains("项目授权已撤销，请重新绑定");
        assertThat(bridge.records().getFirst().status()).isEqualTo(RunStatus.DENIED);
        assertThat(bridge.records().getFirst().errorMessage()).isEqualTo("项目授权已撤销，请重新绑定");
        assertThat(events.toString()).contains("项目授权已撤销，请重新绑定").doesNotContain("PRIVATE_INPUT");
        assertThat(logs).doesNotContain("PRIVATE_INPUT");
    }

    @Test void 项目审批摘要不含正文但仍绑定完整输入() {
        var request = request(true, true, "protected");
        Map<String,Object> arguments = Map.of("value", "PRIVATE_APPROVAL_BODY");
        var asking = ToolUseBlock.builder().id("call-asking").name("protected")
                .input(arguments).state(ToolCallState.ASKING).build();
        var message = Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(asking).generateReason(GenerateReason.PERMISSION_ASKING).build();
        var result = AgentScopeReActExecutor.completedResult(message, List.of(), null,
                request.tools(), new ObjectMapper(), true, Set.of(TOOL));
        var item = result.pendingApproval().items().getFirst();
        assertThat(item.inputSummary()).isEqualTo(AgentScopeToolBridge.PROTECTED_INPUT_SUMMARY);
        assertThat(item.inputHash()).isEqualTo(AgentScopeReActExecutor.approvalInputHash(new ObjectMapper(), arguments));
        assertThat(result.pendingApproval().toString()).doesNotContain("PRIVATE_APPROVAL_BODY");
    }

    @Test void 项目终态删除原始思考但保留最终助手回答() {
        var message = message();
        var result = AgentScopeReActExecutor.completedResult(message, List.of(), null, List.of(),
                new ObjectMapper(), true, Set.of());
        assertThat(result.assistantMessage().contentBlocks()).extracting(MessageContentBlock::text).containsExactly("最终助手回答");
        assertThat(result.output()).isEqualTo("最终助手回答");
        var legacy = AgentScopeReActExecutor.completedResult(message, List.of());
        assertThat(legacy.assistantMessage().contentBlocks()).extracting(MessageContentBlock::text)
                .contains("PRIVATE_THINKING_BODY", "最终助手回答");
    }

    @Test void 实际执行事件通道不转发项目思考或原生工具正文(CapturedOutput logs) {
        var request = request(true, true, "protected");
        var events = new CopyOnWriteArrayList<AgentProgressEvent>();
        var lifecycle = new AgentScopeReActExecutor.AgentLifecycle() {
            @Override public Flux<AgentEvent> streamEvents(ReActAgent agent, Msg input, RuntimeContext context) {
                return Flux.just(new ThinkingBlockStartEvent("reply", "thinking"),
                        new ThinkingBlockDeltaEvent("reply", "thinking", "PRIVATE_THINKING_BODY"),
                        new ToolResultTextDeltaEvent("reply", "call", "protected", "PRIVATE_TOOL_DELTA"),
                        new ThinkingBlockEndEvent("reply", "thinking"),
                        new AgentResultEvent(message()));
            }
            @Override public void interrupt(ReActAgent agent, RuntimeContext context) { agent.interrupt(context); }
            @Override public void close(ReActAgent agent) { agent.close(); }
        };
        var executor = new AgentScopeReActExecutor(
                new AgentScopeRuntimeOptions(Duration.ofSeconds(5), Duration.ofSeconds(2), 1, true),
                new AgentScopeModelFactory(), new InMemoryAgentStateStore(), lifecycle);
        var result = executor.executeStructured(new AgentScopeRunSpec(request), new ModelCredential("test-fixture-key"),
                value -> ToolInvocationResult.succeededProtected("PRIVATE_TOOL_BODY", "安全摘要"), ignored -> {}, events::add);
        assertThat(result.output()).isEqualTo("最终助手回答");
        assertThat(events.toString()).doesNotContain("PRIVATE_THINKING_BODY", "PRIVATE_TOOL_DELTA", "PRIVATE_TOOL_BODY");
        assertThat(result.assistantMessage().toString()).doesNotContain("PRIVATE_THINKING_BODY", "PRIVATE_TOOL_DELTA");
        assertThat(logs).doesNotContain("PRIVATE_THINKING_BODY", "PRIVATE_TOOL_DELTA", "PRIVATE_TOOL_BODY");
    }

    private static Msg message() {
        return Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(ThinkingBlock.builder().thinking("PRIVATE_THINKING_BODY").build(),
                        TextBlock.builder().text("最终助手回答").build()).build();
    }

    private static AgentRunRequest request(boolean project, boolean protectedId, String name) {
        var tool = new ToolDefinition(TOOL, TENANT, name, "测试工具", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}}}", ToolRiskLevel.LOW, true, "", "tester", "tester");
        var agent = new AgentDefinition(AGENT, TENANT, "测试Agent", "", "测试", MODEL, "fixture-model",
                0.2, 4, true, List.of(TOOL), "tester", "tester");
        var model = new ModelConfig(MODEL, TENANT, ModelProviderType.OPENAI_COMPATIBLE, "测试模型",
                "https://example.invalid/v1", "fixture-model", true);
        var principal = new PrincipalRef(TENANT, "owner", "测试", Set.of("agent:run"));
        var binding = new LocalProjectBinding(TENANT, "owner", UUID.randomUUID(), AGENT, CONVERSATION, RUN,
                UUID.randomUUID(), "a".repeat(64), "local-node", 1, Instant.now());
        return new AgentRunRequest(RUN, TENANT, agent, model, principal, "测试输入", List.of(tool), CONVERSATION,
                List.of(), Map.of(), new RunCancellation(), null,
                project ? new ProjectRuntimeContext(binding, protectedId ? Set.of(TOOL) : Set.of()) : null);
    }

    private static ToolCallParam call(String name, String value) {
        Map<String,Object> input = Map.of("value",value);
        return ToolCallParam.builder().toolUseBlock(ToolUseBlock.builder().id("tool-call").name(name).input(input).build())
                .input(input).runtimeContext(RuntimeContext.builder().sessionId(RUN.toString()).build()).build();
    }
    private static String modelText(ToolResultBlock block) {
        return block.getOutput().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast).map(TextBlock::getText)
                .reduce("",String::concat);
    }
}

