package com.cmagent.server.runtime.http;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolInvocationSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import javax.net.ssl.SSLHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicHttpToolExecutorTest {
    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String SECRET_REF = "secret/integration/api-key";
    private static final String SECRET_VALUE = "Bearer unit-test-secret-value";
    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{
              "id":{"type":"string"},"query":{"type":"string"},"name":{"type":"string"}
            }}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private HttpToolProperties properties;
    private AtomicInteger dnsResolutions;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        port = server.getAddress().getPort();
        properties = new HttpToolProperties();
        properties.setEnabled(true);
        properties.setAllowHttp(true);
        properties.setAllowedHosts(Set.of("127.0.0.1"));
        dnsResolutions = new AtomicInteger();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void disabledReturnsFixedFailureBeforeSecretUriDnsOrNetwork() {
        properties.setEnabled(false);
        AtomicInteger secretResolutions = new AtomicInteger();
        DynamicHttpToolExecutor executor = executor((tenantId, secretRef) -> {
            secretResolutions.incrementAndGet();
            return java.util.Optional.of(SECRET_VALUE);
        });
        HttpToolConfig invalidUrlConfig = config(HttpToolMethod.GET, "不是 URI", List.of(),
                Map.of("Authorization", SECRET_REF), Duration.ofSeconds(1));

        ToolExecutionResult result = executor.execute(tool("不是 URI"), invalidUrlConfig, request("{}"));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 工具未启用", null));
        assertThat(secretResolutions.get()).isZero();
        assertThat(dnsResolutions.get()).isZero();
    }

    @Test
    void missingSecretFailsBeforeDnsAndNetwork() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/secret", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 200, "application/json", "{}");
        });
        DynamicHttpToolExecutor executor = executor((tenantId, secretRef) -> java.util.Optional.empty());
        HttpToolConfig config = config(HttpToolMethod.GET, url("/secret"), List.of(),
                Map.of("Authorization", SECRET_REF), Duration.ofSeconds(1));

        ToolExecutionResult result = executor.execute(tool(config.urlTemplate()), config, request("{}"));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP Secret 不可用", null));
        assertThat(dnsResolutions.get()).isZero();
        assertThat(hits.get()).isZero();
    }

    @Test
    void getEncodesPathAndQueryInjectsSecretAndRedactsResponse() {
        AtomicReference<String> rawPath = new AtomicReference<>();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/items", exchange -> {
            rawPath.set(exchange.getRequestURI().getRawPath());
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "application/json; charset=utf-8",
                    "{\"token\":\"" + SECRET_VALUE + "\",\"ok\":true}");
        });
        List<HttpParameterMapping> mappings = List.of(
                mapping("/id", HttpParameterLocation.PATH, "id", "", true),
                mapping("/query", HttpParameterLocation.QUERY, "q", "", false)
        );
        HttpToolConfig config = config(HttpToolMethod.GET, url("/items/{id}"), mappings,
                Map.of("Authorization", SECRET_REF), Duration.ofSeconds(1));

        ToolExecutionResult result = executor(secretProvider()).execute(
                tool(config.urlTemplate()), config, request("{\"id\":\"a/b 空格\",\"query\":\"x&y=1\"}"));

        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(rawPath.get()).isEqualTo("/items/a%2Fb%20%E7%A9%BA%E6%A0%BC");
        assertThat(rawQuery.get()).isEqualTo("q=x%26y%3D1");
        assertThat(authorization.get()).isEqualTo(SECRET_VALUE);
        assertThat(result.outputSummary()).contains("\"ok\":true").doesNotContain(SECRET_VALUE);
        assertThat(result.toString()).doesNotContain(SECRET_VALUE).doesNotContain("Authorization");
    }

    @Test
    void postSendsMappedJsonBodyAndContentTypeOnce() {
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        server.createContext("/orders", exchange -> {
            hits.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            respond(exchange, 201, "text/plain; charset=utf-8", "已创建");
        });
        HttpParameterMapping bodyMapping = mapping(
                "/name", HttpParameterLocation.BODY, "", "/customer/name", true);
        HttpToolConfig config = config(HttpToolMethod.POST, url("/orders"), List.of(bodyMapping),
                Map.of(), Duration.ofSeconds(1));

        ToolExecutionResult result = executor(secretProvider()).execute(
                tool(config.urlTemplate()), config, request("{\"name\":\"张三\"}"));

        assertThat(result.success()).isTrue();
        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(result.outputSummary()).isEqualTo("已创建");
        assertThat(body.get()).isEqualTo("{\"customer\":{\"name\":\"张三\"}}");
        assertThat(contentType.get()).startsWith("application/json");
        assertThat(hits.get()).isOne();
    }

    @Test
    void nonSuccessStatusNeverReturnsOrReadsBody() {
        String sensitiveBody = "password=不得泄露";
        server.createContext("/failed", exchange -> respond(
                exchange, 503, "text/plain", sensitiveBody));
        HttpToolConfig config = config(HttpToolMethod.GET, url("/failed"), List.of(), Map.of(),
                Duration.ofSeconds(1));

        ToolExecutionResult result = executor(secretProvider()).execute(
                tool(config.urlTemplate()), config, request("{}"));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 服务返回非成功状态", 503));
        assertThat(result.toString()).doesNotContain(sensitiveBody).doesNotContain("不得泄露");
    }

    @Test
    void rejectsBinaryAndOversizedResponses() {
        server.createContext("/binary", exchange -> respond(
                exchange, 200, "application/octet-stream", "binary-secret"));
        server.createContext("/large", exchange -> respond(
                exchange, 200, "text/plain", "123456789"));
        properties.setMaxResponseBytes(8);

        ToolExecutionResult binary = executeGet("/binary", Duration.ofSeconds(1));
        ToolExecutionResult large = executeGet("/large", Duration.ofSeconds(1));

        assertThat(binary).isEqualTo(ToolExecutionResult.failed("HTTP 响应类型不受支持", 200));
        assertThat(large).isEqualTo(ToolExecutionResult.failed("HTTP 响应超过大小限制", 200));
        assertThat(binary.toString()).doesNotContain("binary-secret");
        assertThat(large.toString()).doesNotContain("123456789");
    }

    @Test
    void resultRedactionRemovesAuthorizationCookieSensitiveQueryAndCompleteUrl() {
        String response = """
                Authorization: Bearer response-auth-value
                Cookie: session=response-cookie-value
                target=https://api.example.com/orders?token=response-query-value
                """;
        server.createContext("/redaction", exchange -> respond(
                exchange, 200, "text/plain", response));

        ToolExecutionResult result = executeGet("/redaction", Duration.ofSeconds(1));

        assertThat(result.success()).isTrue();
        assertThat(result.outputSummary())
                .doesNotContain("Authorization")
                .doesNotContain("Cookie")
                .doesNotContain("response-auth-value")
                .doesNotContain("response-cookie-value")
                .doesNotContain("response-query-value")
                .doesNotContain("https://api.example.com");
    }

    @Test
    void timeoutReturnsFixedFailureWithoutLeakingUrl() {
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(300);
                respond(exchange, 200, "text/plain", "late");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                exchange.close();
            }
        });

        ToolExecutionResult result = executeGet("/slow?token=不得泄露", Duration.ofMillis(100));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 请求超时", null));
        assertThat(result.toString()).doesNotContain("不得泄露").doesNotContain(url("/slow"));
    }

    @Test
    void rejectsTimeoutOutsideConfiguredBoundsBeforeDnsAndNetwork() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/timeout-bounds", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 200, "text/plain", "unexpected");
        });

        ToolExecutionResult tooShort = executeGet("/timeout-bounds", Duration.ofMillis(99));
        ToolExecutionResult tooLong = executeGet("/timeout-bounds", Duration.ofSeconds(31));

        assertThat(tooShort).isEqualTo(ToolExecutionResult.failed("HTTP 超时配置不允许", null));
        assertThat(tooLong).isEqualTo(ToolExecutionResult.failed("HTTP 超时配置不允许", null));
        assertThat(dnsResolutions.get()).isZero();
        assertThat(hits.get()).isZero();
    }

    @Test
    void followsRelativeRedirectsAndRevalidatesEveryHop() {
        server.createContext("/redirect", exchange -> redirect(exchange, 302, "/final"));
        server.createContext("/final", exchange -> respond(exchange, 200, "text/plain", "完成"));

        ToolExecutionResult result = executeGet("/redirect", Duration.ofSeconds(1));

        assertThat(result.success()).isTrue();
        assertThat(result.outputSummary()).isEqualTo("完成");
        assertThat(dnsResolutions.get()).isEqualTo(2);
    }

    @Test
    void blockedRedirectTargetIsNeverRequested() {
        AtomicInteger privateHits = new AtomicInteger();
        server.createContext("/redirect", exchange -> redirect(
                exchange, 302, "http://localhost:" + port + "/private"));
        server.createContext("/private", exchange -> {
            privateHits.incrementAndGet();
            respond(exchange, 200, "text/plain", "不得访问");
        });

        ToolExecutionResult result = executeGet("/redirect", Duration.ofSeconds(1));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 目标地址不允许", null));
        assertThat(privateHits.get()).isZero();
    }

    @Test
    void stopsAfterConfiguredRedirectLimit() {
        AtomicInteger hits = new AtomicInteger();
        for (int index = 0; index <= 4; index++) {
            int current = index;
            server.createContext("/r" + index, exchange -> {
                hits.incrementAndGet();
                redirect(exchange, 302, "/r" + (current + 1));
            });
        }

        ToolExecutionResult result = executeGet("/r0", Duration.ofSeconds(1));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 重定向次数超限", 302));
        assertThat(hits.get()).isEqualTo(4);
    }

    @Test
    void invalidSecretHeaderNameFailsBeforeDnsAndNetwork() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/headers", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 200, "text/plain", "unexpected");
        });
        HttpToolConfig config = config(HttpToolMethod.GET, url("/headers"), List.of(),
                Map.of("Host", SECRET_REF), Duration.ofSeconds(1));

        ToolExecutionResult result = executor(secretProvider()).execute(
                tool(config.urlTemplate()), config, request("{}"));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 请求头配置不安全", null));
        assertThat(dnsResolutions.get()).isZero();
        assertThat(hits.get()).isZero();
    }

    @Test
    void doesNotRetryNonSuccessResponse() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/once", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 502, "text/plain", "bad gateway");
        });

        ToolExecutionResult result = executeGet("/once", Duration.ofSeconds(1));

        assertThat(result.success()).isFalse();
        assertThat(hits.get()).isOne();
    }

    @Test
    void tlsFailureUsesFixedRedactedError() {
        HttpToolUrlPolicy urlPolicy = new HttpToolUrlPolicy(properties, host ->
                List.of(InetAddress.getByName("93.184.216.34")));
        HttpToolInputMapper inputMapper = new HttpToolInputMapper(
                objectMapper, new HttpToolConfigValidator(objectMapper));
        DynamicHttpToolExecutor executor = new DynamicHttpToolExecutor(
                properties, secretProvider(), urlPolicy, inputMapper, objectMapper,
                request -> {
                    throw new SSLHandshakeException("Bearer 不得泄露");
                });
        HttpToolConfig config = config(HttpToolMethod.GET, url("/tls"), List.of(), Map.of(),
                Duration.ofSeconds(1));

        ToolExecutionResult result = executor.execute(tool(config.urlTemplate()), config, request("{}"));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP TLS 连接失败", null));
        assertThat(result.toString()).doesNotContain("不得泄露").doesNotContain("Bearer");
    }

    @Test
    void connectionFailureUsesFixedErrorWithoutCompleteUrl() throws Exception {
        HttpToolUrlPolicy urlPolicy = new HttpToolUrlPolicy(properties, host ->
                List.of(InetAddress.getByName("93.184.216.34")));
        HttpToolInputMapper inputMapper = new HttpToolInputMapper(
                objectMapper, new HttpToolConfigValidator(objectMapper));
        DynamicHttpToolExecutor executor = new DynamicHttpToolExecutor(
                properties, secretProvider(), urlPolicy, inputMapper, objectMapper,
                request -> {
                    throw new ConnectException("http://127.0.0.1/private?token=不得泄露");
                });
        String closedUrl = url("/private?token=不得泄露");
        HttpToolConfig config = config(HttpToolMethod.GET, closedUrl, List.of(), Map.of(),
                Duration.ofMillis(500));

        ToolExecutionResult result = executor.execute(
                tool(config.urlTemplate()), config, request("{}"));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 连接失败", null));
        assertThat(result.toString()).doesNotContain("不得泄露").doesNotContain(closedUrl);
    }

    private ToolExecutionResult executeGet(String path, Duration timeout) {
        HttpToolConfig config = config(HttpToolMethod.GET, url(path), List.of(), Map.of(), timeout);
        return executor(secretProvider()).execute(tool(config.urlTemplate()), config, request("{}"));
    }

    private DynamicHttpToolExecutor executor(HttpToolSecretProvider secretProvider) {
        HostAddressResolver resolver = host -> {
            dnsResolutions.incrementAndGet();
            return List.of(InetAddress.getByName("93.184.216.34"));
        };
        HttpToolUrlPolicy urlPolicy = new HttpToolUrlPolicy(properties, resolver);
        HttpToolInputMapper inputMapper = new HttpToolInputMapper(
                objectMapper, new HttpToolConfigValidator(objectMapper));
        return new DynamicHttpToolExecutor(properties, secretProvider, urlPolicy, inputMapper, objectMapper);
    }

    private HttpToolSecretProvider secretProvider() {
        return (tenantId, secretRef) -> java.util.Optional.of(SECRET_VALUE);
    }

    private HttpToolConfig config(HttpToolMethod method, String url, List<HttpParameterMapping> mappings,
                                  Map<String, String> secrets, Duration timeout) {
        return new HttpToolConfig(TENANT_ID, TOOL_ID, method, url, INPUT_SCHEMA, mappings, secrets, timeout);
    }

    private ToolDefinition tool(String endpoint) {
        return new ToolDefinition(TOOL_ID, TENANT_ID, "http-test", "HTTP 测试", ToolType.HTTP,
                INPUT_SCHEMA, ToolRiskLevel.LOW, true, endpoint, "tester", "tester");
    }

    private ToolExecutionRequest request(String input) {
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "tester", "测试用户", Set.of());
        return new ToolExecutionRequest(TENANT_ID, null, principal, null, "call-1", TOOL_ID, input,
                ToolInvocationSource.DEBUG);
    }

    private HttpParameterMapping mapping(String source, HttpParameterLocation location,
                                         String targetName, String targetPointer, boolean required) {
        return new HttpParameterMapping(source, location, targetName, targetPointer, required, "");
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static void redirect(HttpExchange exchange, int status, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
