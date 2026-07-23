package com.cmagent.server.mcp;

import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = CmAgentServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "cm-agent.mcp.enabled=false"
)
@ActiveProfiles("test")
class McpServerDisabledIntegrationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Test
    void 关闭时端点不存在并返回404() throws Exception {
        String token = jwtService.createToken(
                UUID.fromString("00000000-0000-0000-0000-000000000829"),
                "mcp-user", "MCP 用户", List.of("tool:mcp:invoke")
        );
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }
}
