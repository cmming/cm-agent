package com.cmagent.examples.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CmAgentToolClientTest {
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpToolExampleProperties properties;
    private MockRestServiceServer mockServer;
    private CmAgentToolClient client;

    @BeforeEach
    /**
     * 准备每个测试用例共享的前置数据。
     */
    void setUp() {
        properties = new HttpToolExampleProperties();
        properties.setBaseUrl("http://localhost:8080");
        properties.setJwt("test-jwt");
        properties.setToolName("developer-http-example");
        properties.setTargetUrl("https://api.example.test/messages");
        properties.setSecretHeaderName("X-Api-Key");
        properties.setSecretRef("secret/integration/example-api-key");
        properties.setMessage("你好，CM Agent");

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new CmAgentToolClient(builder, objectMapper, properties);
    }

    @Test
    /**
     * 验证或支持 {@code shouldCreateAndDebugHttpToolThroughPublicApi} 所描述的测试场景。
     */
    void shouldCreateAndDebugHttpToolThroughPublicApi() {
        mockServer.expect(requestTo("http://localhost:8080/api/tools"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-jwt"))
                .andExpect(jsonPath("$.name").value("developer-http-example"))
                .andExpect(jsonPath("$.type").value("HTTP"))
                .andExpect(jsonPath("$.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.mcpPublished").value(false))
                .andExpect(jsonPath("$.httpConfig.method").value("POST"))
                .andExpect(jsonPath("$.httpConfig.urlTemplate").value("https://api.example.test/messages"))
                .andExpect(jsonPath("$.httpConfig.inputSchema.type").value("object"))
                .andExpect(jsonPath("$.httpConfig.parameterMappings[0].sourcePointer").value("/message"))
                .andExpect(jsonPath("$.httpConfig.parameterMappings[0].location").value("BODY"))
                .andExpect(jsonPath("$.httpConfig.parameterMappings[0].targetPointer").value("/message"))
                .andExpect(jsonPath("$.httpConfig.secretHeaders.X-Api-Key")
                        .value("secret/integration/example-api-key"))
                .andRespond(withSuccess(createdToolResponse(), MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("http://localhost:8080/api/tools/" + TOOL_ID + "/debug"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-jwt"))
                .andExpect(jsonPath("$.input.message").value("你好，CM Agent"))
                .andRespond(withSuccess("""
                        {"success":true,"statusCode":200,"output":"ok","errorMessage":"","durationMillis":5}
                        """, MediaType.APPLICATION_JSON));

        CmAgentToolClient.ExampleResult result = client.createAndDebug();

        assertThat(result.toolId()).isEqualTo(TOOL_ID);
        assertThat(result.debugResponse().path("success").asBoolean()).isTrue();
        mockServer.verify();
    }

    @Test
    /**
     * 验证或支持 {@code shouldRedactJwtFromErrorMessage} 所描述的测试场景。
     */
    void shouldRedactJwtFromErrorMessage() {
        mockServer.expect(requestTo("http://localhost:8080/api/tools"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"message":"令牌 test-jwt 无效"}
                                """));

        assertThatThrownBy(client::createAndDebug)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("test-jwt")
                .hasMessageContaining("<已脱敏>");
        mockServer.verify();
    }

    /**
     * 验证或支持 {@code createdToolResponse} 所描述的测试场景。
     */
    private String createdToolResponse() {
        return """
                {
                  "id":"00000000-0000-0000-0000-000000000201",
                  "tenantId":"00000000-0000-0000-0000-000000000001",
                  "name":"developer-http-example",
                  "description":"通过公开 API 创建的 HTTP 工具示例",
                  "type":"HTTP",
                  "inputSchema":"{\\"type\\":\\"object\\"}",
                  "riskLevel":"LOW",
                  "enabled":true,
                  "endpoint":"https://api.example.test/messages",
                  "createdBy":"example",
                  "updatedBy":"example",
                  "httpConfig":{
                    "method":"POST",
                    "urlTemplate":"https://api.example.test/messages",
                    "inputSchema":"{\\"type\\":\\"object\\"}",
                    "parameterMappings":[],
                    "secretHeaders":{"X-Api-Key":"secret/integration/example-api-key"},
                    "timeoutMillis":5000
                  },
                  "mcpPublished":false
                }
                """;
    }
}
