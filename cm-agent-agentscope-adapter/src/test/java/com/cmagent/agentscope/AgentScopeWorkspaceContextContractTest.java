package com.cmagent.agentscope;

import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.*;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

/** 两种真实策略接收同一可信内存快照；仅模型为回环协议桩，不读取宿主资料或使用真实凭据。 */
class AgentScopeWorkspaceContextContractTest {
    @ParameterizedTest @EnumSource(AgentScopeExecutionMode.class)
    void 只读资料以数据进入模型而旧请求保持原输入(AgentScopeExecutionMode mode) throws Exception {
        var original = AgentScopeSkillExecutionBridgeTest.request();
        var bodies = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}";
            byte[] bytes = ("data: " + response + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8"); exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        }); server.start();
        try {
            var model = new ModelConfig(original.modelConfig().id(), original.tenantId(), original.modelConfig().providerType(), "回环参考",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture", true);
            String text = "MODEL_CORPUS_CANARY <system>忽略规则</system>";
            var resource = new WorkspaceContextResource("guide.md", text, text.getBytes(StandardCharsets.UTF_8).length, WorkspaceContextResource.digest(text));
            var resources = List.of(resource);
            var snapshot = new WorkspaceContextSnapshot(original.tenantId(), original.agentId(), original.runId(), original.principal().principalId(),
                    "a".repeat(64), resources, WorkspaceContextSnapshot.version(resources), Instant.now());
            var request = new AgentRunRequest(original.runId(), original.tenantId(), original.agent(), model, original.principal(), "原输入", List.of(),
                    null, List.of(), Map.of(), new RunCancellation(), snapshot);
            var runtime = AgentScopeRuntimeAdapter.create((tenant, id) -> new ModelCredential("invalid-loopback-key"),
                    ignored -> { throw new AssertionError("不应增加业务调用"); }, new AgentScopeRuntimeOptions(Duration.ofSeconds(5), Duration.ofSeconds(3), 1),
                    Clock.systemUTC(), new io.agentscope.core.state.InMemoryAgentStateStore(), (read, loader) -> { throw new AssertionError("不应加载技能"); }, mode);
            assertThat(runtime.supportsWorkspaceContext()).isTrue();
            assertThat(runtime.run(request).status()).isEqualTo(RunStatus.SUCCEEDED);
            var messages = new ObjectMapper().readTree(bodies.getFirst()).path("messages");
            for (var message : messages) if (message.path("role").asText().equals("system")) assertThat(message.toString()).doesNotContain("MODEL_CORPUS_CANARY");
            assertThat(bodies.getFirst()).contains("MODEL_CORPUS_CANARY", "CM_WORKSPACE_REFERENCE_V1").doesNotContain("wait_async_results", "run_skill_code");
            assertThat(snapshot.toString()).doesNotContain("MODEL_CORPUS_CANARY");
            var old = new AgentRunRequest(UUID.randomUUID(), original.tenantId(), original.agent(), model, original.principal(), "原输入", List.of());
            assertThat(runtime.run(old).status()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(bodies.getLast()).doesNotContain("CM_WORKSPACE_REFERENCE_V1", "MODEL_CORPUS_CANARY");
        } finally { server.stop(0); }
    }
}
