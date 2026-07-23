package com.cmagent.server.mcp;

import com.cmagent.core.domain.McpToolPublication;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolRegistry;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.store.InMemoryPlatformStore;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = CmAgentServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "cm-agent.mcp.enabled=true",
                "cm-agent.mcp.endpoint=/mcp",
                "cm-agent.mcp.allowed-origins[0]=https://client.example.test",
                "cm-agent.mcp.allowed-hosts[0]=localhost:*"
        }
)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class McpServerIntegrationTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000821");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000822");
    private static final UUID TOOL_A = UUID.fromString("10000000-0000-0000-0000-000000000821");
    private static final UUID TOOL_B = UUID.fromString("10000000-0000-0000-0000-000000000822");

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private InMemoryPlatformStore store;

    @Autowired
    private ToolRegistry registry;

    @Test
    void 官方客户端完成InitializeListCall且取消发布和Schema校验即时生效() {
        AtomicInteger executions = new AtomicInteger();
        publishLocal(TOOL_A, TENANT_A, "tenant_a_echo", executions);
        String token = token(TENANT_A, "tenant-a", List.of("tool:mcp:invoke"));

        try (McpSyncClient client = client(token)) {
            McpSchema.InitializeResult initialized = client.initialize();
            assertThat(initialized.serverInfo().name()).isEqualTo("cm-agent");
            assertThat(initialized.serverInfo().version()).isEqualTo("0.1.0");
            assertThat(client.listTools().tools())
                    .extracting(McpSchema.Tool::name)
                    .containsExactly("tenant_a_echo");

            McpSchema.CallToolResult invalid = client.callTool(
                    new McpSchema.CallToolRequest("tenant_a_echo", Map.of())
            );
            assertThat(invalid.isError()).isTrue();
            assertThat(executions).hasValue(0);

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("tenant_a_echo", Map.of("value", "你好"))
            );
            assertThat(result.isError()).isFalse();
            assertThat(result.content()).singleElement().isInstanceOfSatisfying(
                    McpSchema.TextContent.class,
                    content -> assertThat(content.text()).contains("你好")
            );
            assertThat(executions).hasValue(1);

            store.deleteMcpToolPublication(TENANT_A, TOOL_A);
            assertThat(client.listTools().tools()).isEmpty();
        }
    }

    @Test
    void Jwt权限Get以及OriginHost边界返回受控Http状态并记录拒绝审计() throws Exception {
        publishLocal(TOOL_A, TENANT_A, "tenant_a_echo", new AtomicInteger());
        String allowed = token(TENANT_A, "allowed", List.of("tool:mcp:invoke"));
        String denied = token(TENANT_A, "denied", List.of("tool:read"));

        assertThat(post("localhost", null, "https://client.example.test").statusCode()).isEqualTo(401);
        HttpResponse<String> deniedResponse = post("localhost", denied, "https://client.example.test");
        assertThat(deniedResponse.statusCode()).isEqualTo(403);
        assertThat(deniedResponse.body()).doesNotContain("tool:mcp:invoke");
        assertThat(store.listAuditEvents(TENANT_A)).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("ACCESS_DENIED");
            assertThat(event.resourceType()).isEqualTo("MCP");
            assertThat(event.message()).contains("tool:mcp:invoke");
        });

        assertThat(get(allowed).statusCode()).isEqualTo(405);
        assertThat(post("localhost", allowed, "https://evil.example.test").statusCode()).isEqualTo(403);
        assertThat(post("127.0.0.1", allowed, "https://client.example.test").statusCode()).isEqualTo(421);
    }

    @Test
    void 并发官方客户端请求严格隔离各自租户() throws Exception {
        publishLocal(TOOL_A, TENANT_A, "tenant_a_echo", new AtomicInteger());
        publishLocal(TOOL_B, TENANT_B, "tenant_b_echo", new AtomicInteger());
        String tokenA = token(TENANT_A, "tenant-a", List.of("tool:mcp:invoke"));
        String tokenB = token(TENANT_B, "tenant-b", List.of("tool:mcp:invoke"));
        List<Callable<String>> requests = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            String token = index % 2 == 0 ? tokenA : tokenB;
            requests.add(() -> {
                try (McpSyncClient client = client(token)) {
                    client.initialize();
                    return client.listTools().tools().getFirst().name();
                }
            });
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<String> names = executor.invokeAll(requests).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();
            for (int index = 0; index < names.size(); index++) {
                assertThat(names.get(index)).isEqualTo(index % 2 == 0 ? "tenant_a_echo" : "tenant_b_echo");
            }
        }
    }

    private McpSyncClient client(String token) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl("localhost"))
                .endpoint("/mcp")
                .resumableStreams(false)
                .openConnectionOnStartup(false)
                .httpRequestCustomizer((builder, method, uri, body, context) -> builder
                        .header("Authorization", "Bearer " + token)
                        .header("Origin", "https://client.example.test"))
                .build();
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(5))
                .initializationTimeout(Duration.ofSeconds(5))
                .build();
    }

    private HttpResponse<String> post(String host, String token, String origin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl(host) + "/mcp"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                        """));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl("localhost") + "/mcp"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://client.example.test")
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl(String host) {
        return "http://" + host + ":" + port;
    }

    private String token(UUID tenantId, String principalId, List<String> permissions) {
        return jwtService.createToken(tenantId, principalId, "MCP 测试用户", permissions);
    }

    private void publishLocal(UUID toolId, UUID tenantId, String name, AtomicInteger executions) {
        ToolDefinition tool = new ToolDefinition(
                toolId, tenantId, name, "租户回显工具", ToolType.LOCAL,
                "{\"type\":\"object\",\"required\":[\"value\"],\"properties\":{\"value\":{\"type\":\"string\"}},\"additionalProperties\":false}",
                ToolRiskLevel.LOW, true, "", "admin", "admin"
        );
        store.saveTool(tool);
        registry.register(tool, request -> {
            executions.incrementAndGet();
            return ToolExecutionResult.succeeded(request.inputJson(), null);
        });
        store.saveMcpToolPublication(new McpToolPublication(tenantId, toolId, true, "admin"));
    }
}
