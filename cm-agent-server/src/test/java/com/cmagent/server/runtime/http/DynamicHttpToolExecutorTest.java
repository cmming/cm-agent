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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    void blockingSecretProviderUsesCallDeadlineWithoutDnsOrNetwork() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch interrupted = new CountDownLatch(1);
        server.createContext("/blocking-secret", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 200, "text/plain", "不应访问");
        });
        HttpToolSecretProvider blockingProvider = (tenantId, secretRef) -> {
            try {
                new CountDownLatch(1).await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return java.util.Optional.of("不得发送");
        };
        HttpToolConfig config = config(HttpToolMethod.GET, url("/blocking-secret"), List.of(),
                Map.of("Authorization", "secret/blocking-provider-ref"), Duration.ofMillis(100));
        long started = System.nanoTime();

        ToolExecutionResult result = executor(blockingProvider).execute(
                tool(config.urlTemplate()), config, request("{}"));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 工具调用超时", null));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(result.toString()).doesNotContain("blocking-provider-ref", "不得发送");
        assertThat(dnsResolutions.get()).isZero();
        assertThat(hits.get()).isZero();
    }

    @Test
    void interruptedSecretProviderReturnDoesNotContinueWithAnotherReference() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicInteger lookups = new AtomicInteger();
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch subsequentLookup = new CountDownLatch(1);
        server.createContext("/interrupted-secret-loop", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 200, "text/plain", "不应访问");
        });
        HttpToolSecretProvider interruptRestoringProvider = (tenantId, secretRef) -> {
            if (lookups.incrementAndGet() > 1) {
                subsequentLookup.countDown();
                return java.util.Optional.of("不应查询");
            }
            try {
                new CountDownLatch(1).await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
            }
            return java.util.Optional.of("首个值");
        };
        HttpToolConfig config = config(HttpToolMethod.GET, url("/interrupted-secret-loop"), List.of(),
                Map.of("Authorization", "secret/first", "X-Api-Key", "secret/second"),
                Duration.ofMillis(100));

        ToolExecutionResult result = executor(interruptRestoringProvider).execute(
                tool(config.urlTemplate()), config, request("{}"));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 工具调用超时", null));
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(subsequentLookup.await(250, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(lookups.get()).isOne();
        assertThat(dnsResolutions.get()).isZero();
        assertThat(hits.get()).isZero();
    }

    @Test
    void multipleSecretLookupsShareOneCallDeadline() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicInteger lookups = new AtomicInteger();
        CountDownLatch interrupted = new CountDownLatch(1);
        server.createContext("/multiple-secrets", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 200, "text/plain", "不应访问");
        });
        HttpToolSecretProvider cumulativeProvider = (tenantId, secretRef) -> {
            lookups.incrementAndGet();
            try {
                Thread.sleep(120);
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return java.util.Optional.of("不得发送");
        };
        HttpToolConfig config = config(HttpToolMethod.GET, url("/multiple-secrets"), List.of(),
                Map.of("Authorization", "secret/first", "X-Api-Key", "secret/second"),
                Duration.ofMillis(200));
        long started = System.nanoTime();

        ToolExecutionResult result = executor(cumulativeProvider).execute(
                tool(config.urlTemplate()), config, request("{}"));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 工具调用超时", null));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(450));
        assertThat(lookups.get()).isEqualTo(2);
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(dnsResolutions.get()).isZero();
        assertThat(hits.get()).isZero();
    }

    @Test
    void secretProviderExceptionUsesFixedFailureWithoutLeakingDetails() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/secret-exception", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 200, "text/plain", "不应访问");
        });
        HttpToolSecretProvider failingProvider = (tenantId, secretRef) -> {
            throw new IllegalStateException("provider 异常包含 secret/provider-exception-ref");
        };
        HttpToolConfig config = config(HttpToolMethod.GET, url("/secret-exception"), List.of(),
                Map.of("Authorization", "secret/provider-exception-ref"), Duration.ofSeconds(1));

        ToolExecutionResult result = executor(failingProvider).execute(
                tool(config.urlTemplate()), config, request("{}"));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP Secret 不可用", null));
        assertThat(result.toString()).doesNotContain("provider-exception-ref", "provider 异常");
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
        AtomicInteger contentTypeCount = new AtomicInteger();
        server.createContext("/orders", exchange -> {
            hits.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            contentTypeCount.set(exchange.getRequestHeaders().get("Content-Type").size());
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
        assertThat(contentTypeCount.get()).isOne();
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
    void requestsIdentityEncodingAndRejectsEncodedOrAmbiguousResponseHeaders() {
        AtomicReference<String> acceptEncoding = new AtomicReference<>();
        server.createContext("/identity", exchange -> {
            acceptEncoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            respond(exchange, 200, "text/plain", "identity");
        });
        server.createContext("/gzip", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            respondWithoutContentType(exchange, 200, "compressed");
        });
        server.createContext("/multi-encoding", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.getResponseHeaders().add("Content-Encoding", "identity");
            exchange.getResponseHeaders().add("Content-Encoding", "identity");
            respondWithoutContentType(exchange, 200, "ambiguous");
        });
        server.createContext("/multi-content-type", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            respondWithoutContentType(exchange, 200, "ambiguous");
        });

        assertThat(executeGet("/identity", Duration.ofSeconds(2)).success()).isTrue();
        assertThat(acceptEncoding.get()).isEqualTo("identity");
        assertThat(executeGet("/gzip", Duration.ofSeconds(2)))
                .isEqualTo(ToolExecutionResult.failed("HTTP 响应编码不受支持", 200));
        assertThat(executeGet("/multi-encoding", Duration.ofSeconds(2)))
                .isEqualTo(ToolExecutionResult.failed("HTTP 响应编码不受支持", 200));
        assertThat(executeGet("/multi-content-type", Duration.ofSeconds(2)))
                .isEqualTo(ToolExecutionResult.failed("HTTP 响应头不安全", 200));
    }

    @Test
    void rejectsMultipleLocationValuesWithoutFollowingRedirect() {
        AtomicInteger finalHits = new AtomicInteger();
        server.createContext("/multi-location", exchange -> {
            exchange.getResponseHeaders().add("Location", "/final-one");
            exchange.getResponseHeaders().add("Location", "/final-two");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final-one", exchange -> {
            finalHits.incrementAndGet();
            respond(exchange, 200, "text/plain", "one");
        });

        ToolExecutionResult result = executeGet("/multi-location", Duration.ofSeconds(2));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 重定向响应头无效", 302));
        assertThat(finalHits.get()).isZero();
    }

    @Test
    void contentTypeAndAcceptEncodingAreReservedRequestHeaders() {
        HttpParameterMapping dynamicContentType = mapping(
                "/name", HttpParameterLocation.HEADER, "Content-Type", "", true);
        HttpToolConfig dynamicConfig = config(HttpToolMethod.POST, url("/reserved"),
                List.of(dynamicContentType), Map.of(), Duration.ofSeconds(1));
        HttpToolConfig secretConfig = config(HttpToolMethod.POST, url("/reserved"), List.of(),
                Map.of("Accept-Encoding", SECRET_REF), Duration.ofSeconds(1));

        ToolExecutionResult dynamic = executor(secretProvider()).execute(
                tool(dynamicConfig.urlTemplate()), dynamicConfig, request("{\"name\":\"text/plain\"}"));
        ToolExecutionResult secret = executor(secretProvider()).execute(
                tool(secretConfig.urlTemplate()), secretConfig, request("{}"));

        assertThat(dynamic).isEqualTo(ToolExecutionResult.failed("HTTP 请求头配置不安全", null));
        assertThat(secret).isEqualTo(ToolExecutionResult.failed("HTTP 请求头配置不安全", null));
    }

    @Test
    void rejectsCrlfInDynamicAndSecretRequestHeaderValuesBeforeNetwork() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/header-injection", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 200, "text/plain", "不应访问");
        });
        HttpParameterMapping headerMapping = mapping(
                "/name", HttpParameterLocation.HEADER, "X-Request-Id", "", true);
        HttpToolConfig dynamicConfig = config(HttpToolMethod.GET, url("/header-injection"),
                List.of(headerMapping), Map.of(), Duration.ofSeconds(1));
        HttpToolConfig secretConfig = config(HttpToolMethod.GET, url("/header-injection"),
                List.of(), Map.of("Authorization", SECRET_REF), Duration.ofSeconds(1));
        HttpToolSecretProvider unsafeSecretProvider = (tenantId, secretRef) ->
                java.util.Optional.of("安全值\r\nX-Evil: 注入");

        ToolExecutionResult dynamic = executor(secretProvider()).execute(
                tool(dynamicConfig.urlTemplate()), dynamicConfig,
                request("{\"name\":\"安全值\\r\\nX-Evil: 注入\"}"));
        ToolExecutionResult secret = executor(unsafeSecretProvider).execute(
                tool(secretConfig.urlTemplate()), secretConfig, request("{}"));

        assertThat(dynamic).isEqualTo(ToolExecutionResult.failed("HTTP 请求头配置不安全", null));
        assertThat(secret).isEqualTo(ToolExecutionResult.failed("HTTP 请求头配置不安全", null));
        assertThat(dnsResolutions.get()).isZero();
        assertThat(hits.get()).isZero();
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
    void jsonRedactionRecursivelyNormalizesSensitiveKeys() throws Exception {
        String response = """
                {
                  "Authorization":"Basic json-authorization",
                  "ACCESS-TOKEN":"json-access-token",
                  "refresh_token":"json-refresh-token",
                  "Api.Key":"json-api-key",
                  "nested":{"PaSs_Word":"json-password","safe":"可见内容"},
                  "items":[
                    {"cookie":"json-cookie"},
                    {"set-cookie":"json-set-cookie"},
                    {"jwt_secret":"json-jwt-secret"},
                    {"client.secret":"json-client-secret"}
                  ]
                }
                """;
        server.createContext("/json-redaction", exchange -> respond(
                exchange, 200, "application/json; charset=utf-8", response));

        ToolExecutionResult result = executeGet("/json-redaction", Duration.ofSeconds(1));

        assertThat(result.success()).isTrue();
        JsonNode redacted = objectMapper.readTree(result.outputSummary());
        assertThat(redacted.path("Authorization").asText()).isEqualTo("<已脱敏>");
        assertThat(redacted.path("ACCESS-TOKEN").asText()).isEqualTo("<已脱敏>");
        assertThat(redacted.path("refresh_token").asText()).isEqualTo("<已脱敏>");
        assertThat(redacted.path("Api.Key").asText()).isEqualTo("<已脱敏>");
        assertThat(redacted.path("nested").path("PaSs_Word").asText()).isEqualTo("<已脱敏>");
        assertThat(redacted.path("nested").path("safe").asText()).isEqualTo("可见内容");
        assertThat(redacted.path("items").get(0).path("cookie").asText()).isEqualTo("<已脱敏>");
        assertThat(redacted.path("items").get(1).path("set-cookie").asText()).isEqualTo("<已脱敏>");
        assertThat(redacted.path("items").get(2).path("jwt_secret").asText()).isEqualTo("<已脱敏>");
        assertThat(redacted.path("items").get(3).path("client.secret").asText()).isEqualTo("<已脱敏>");
        assertThat(result.outputSummary())
                .doesNotContain("json-authorization", "json-access-token", "json-refresh-token",
                        "json-api-key", "json-password", "json-cookie", "json-set-cookie",
                        "json-jwt-secret", "json-client-secret", "Basic");
    }

    @Test
    void textRedactionHandlesQuotedKeysAndNonBearerAuthorization() {
        String response = """
                payload={"access_token":"text-access-token","Api-Key"='text-api-key'}
                Authorization: Basic dGV4dC1hdXRob3JpemF0aW9u
                """;
        server.createContext("/text-redaction", exchange -> respond(
                exchange, 200, "text/plain", response));

        ToolExecutionResult result = executeGet("/text-redaction", Duration.ofSeconds(1));

        assertThat(result.success()).isTrue();
        assertThat(result.outputSummary())
                .doesNotContain("text-access-token", "text-api-key", "dGV4dC1hdXRob3JpemF0aW9u", "Basic");
    }

    @Test
    void textRedactionRemovesExceptionDetailsThroughSharedToolOutputSanitizer() {
        String response = """
                {"access_token":"token-value","client_secret":"secret-value"}
                endpoint=https://private.example.test/a
                Caused by: java.lang.IllegalStateException: hidden
                """;
        server.createContext("/stack-redaction", exchange -> respond(exchange, 200, "text/plain", response));

        ToolExecutionResult result = executeGet("/stack-redaction", Duration.ofSeconds(1));

        assertThat(result.outputSummary()).doesNotContain("token-value", "secret-value", "https://", "Caused by", "IllegalStateException");
    }

    @Test
    void invalidJsonResponseUsesFixedFailure() {
        server.createContext("/invalid-json", exchange -> respond(
                exchange, 200, "application/json", "{\"token\":\"不得泄露\""));

        ToolExecutionResult result = executeGet("/invalid-json", Duration.ofSeconds(1));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP JSON 响应无效", 200));
        assertThat(result.toString()).doesNotContain("不得泄露");
    }

    @Test
    void serializedRedactedJsonStillHonorsResponseSizeLimit() {
        properties.setMaxResponseBytes(12);
        server.createContext("/serialized-limit", exchange -> respond(
                exchange, 200, "application/json", "{\"safe\":\"x\"}"));
        HttpToolConfig config = config(HttpToolMethod.GET, url("/serialized-limit"), List.of(),
                Map.of("Authorization", SECRET_REF), Duration.ofSeconds(1));
        HttpToolSecretProvider shortSecretProvider = (tenantId, secretRef) -> java.util.Optional.of("x");

        ToolExecutionResult result = executor(shortSecretProvider).execute(
                tool(config.urlTemplate()), config, request("{}"));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 响应超过大小限制", 200));
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
    void singleDeadlineIncludesSlowDnsResolution() throws Exception {
        HttpToolUrlPolicy urlPolicy = new HttpToolUrlPolicy(properties, host -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.net.UnknownHostException("解析被中断");
            }
            return List.of(InetAddress.getByName("93.184.216.34"));
        });
        DynamicHttpToolExecutor executor = executor(secretProvider(), urlPolicy);
        HttpToolConfig config = config(HttpToolMethod.GET, url("/slow-dns"), List.of(), Map.of(),
                Duration.ofMillis(100));
        long started = System.nanoTime();

        ToolExecutionResult result = executor.execute(tool(config.urlTemplate()), config, request("{}"));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 请求超时", null));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(220));
    }

    @Test
    void singleDeadlineIsSharedAcrossMultipleSlowRedirects() {
        server.createContext("/deadline-r0", exchange -> slowRedirect(exchange, "/deadline-r1", 70));
        server.createContext("/deadline-r1", exchange -> slowRedirect(exchange, "/deadline-r2", 70));
        server.createContext("/deadline-r2", exchange -> slowRedirect(exchange, "/deadline-final", 70));
        server.createContext("/deadline-final", exchange -> respond(exchange, 200, "text/plain", "太晚"));

        ToolExecutionResult result = executeGet("/deadline-r0", Duration.ofMillis(160));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 请求超时", null));
    }

    @Test
    void singleDeadlineClosesBodyWhenHeadersAreFastButBodyIsSlow() {
        server.createContext("/slow-body", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('a');
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(300);
                exchange.getResponseBody().write('b');
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 客户端超时关闭流是预期行为。
            } finally {
                exchange.close();
            }
        });

        ToolExecutionResult result = executeGet("/slow-body", Duration.ofMillis(100));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 请求超时", null));
    }

    @Test
    void requestCompletesWhenAllPhasesStayWithinSingleDeadline() {
        server.createContext("/within-deadline", exchange -> {
            try {
                Thread.sleep(50);
                respond(exchange, 200, "text/plain", "按时完成");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });

        ToolExecutionResult result = executeGet("/within-deadline", Duration.ofSeconds(2));

        assertThat(result).isEqualTo(ToolExecutionResult.succeeded("按时完成", 200));
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

    @ParameterizedTest
    @ValueSource(ints = {302, 303, 307, 308})
    void sameOriginPostRedirectUsesDefinedMethodAndBodySemantics(int status) {
        AtomicReference<String> finalMethod = new AtomicReference<>();
        AtomicReference<String> finalBody = new AtomicReference<>();
        AtomicReference<String> finalAuthorization = new AtomicReference<>();
        server.createContext("/post-redirect", exchange -> redirect(exchange, status, "/post-final"));
        server.createContext("/post-final", exchange -> {
            finalMethod.set(exchange.getRequestMethod());
            finalBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            finalAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "text/plain", "完成");
        });
        HttpParameterMapping bodyMapping = mapping(
                "/name", HttpParameterLocation.BODY, "", "/name", true);
        HttpToolConfig config = config(HttpToolMethod.POST, url("/post-redirect"), List.of(bodyMapping),
                Map.of("Authorization", SECRET_REF), Duration.ofSeconds(1));

        ToolExecutionResult result = executor(secretProvider()).execute(
                tool(config.urlTemplate()), config, request("{\"name\":\"张三\"}"));

        assertThat(result.success()).isTrue();
        assertThat(finalAuthorization.get()).isEqualTo(SECRET_VALUE);
        if (status == 302 || status == 303) {
            assertThat(finalMethod.get()).isEqualTo("GET");
            assertThat(finalBody.get()).isEmpty();
        } else {
            assertThat(finalMethod.get()).isEqualTo("POST");
            assertThat(finalBody.get()).isEqualTo("{\"name\":\"张三\"}");
        }
    }

    @Test
    void crossOriginRedirectNeverForwardsHeadersSecretsOrPostBody() throws Exception {
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger targetHits = new AtomicInteger();
        AtomicReference<String> targetAuthorization = new AtomicReference<>();
        AtomicReference<String> targetBody = new AtomicReference<>();
        other.createContext("/target", exchange -> {
            targetHits.incrementAndGet();
            targetAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            targetBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "text/plain", "不应访问");
        });
        other.start();
        try {
            String location = "http://127.0.0.1:" + other.getAddress().getPort() + "/target";
            server.createContext("/cross-origin", exchange -> redirect(exchange, 307, location));
            HttpParameterMapping bodyMapping = mapping(
                    "/name", HttpParameterLocation.BODY, "", "/name", true);
            HttpToolConfig config = config(HttpToolMethod.POST, url("/cross-origin"), List.of(bodyMapping),
                    Map.of("Authorization", SECRET_REF), Duration.ofSeconds(1));

            ToolExecutionResult result = executor(secretProvider()).execute(
                    tool(config.urlTemplate()), config, request("{\"name\":\"敏感正文\"}"));

            assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 重定向跨源被拒绝", 307));
            assertThat(targetHits.get()).isZero();
            assertThat(targetAuthorization.get()).isNull();
            assertThat(targetBody.get()).isNull();
        } finally {
            other.stop(0);
        }
    }

    @Test
    void crossOriginBlockedRedirectTargetIsNeverRequested() {
        AtomicInteger privateHits = new AtomicInteger();
        server.createContext("/redirect", exchange -> redirect(
                exchange, 302, "http://localhost:" + port + "/private"));
        server.createContext("/private", exchange -> {
            privateHits.incrementAndGet();
            respond(exchange, 200, "text/plain", "不得访问");
        });

        ToolExecutionResult result = executeGet("/redirect", Duration.ofSeconds(1));

        assertThat(result).isEqualTo(ToolExecutionResult.failed("HTTP 重定向跨源被拒绝", 302));
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
        return executor(secretProvider, urlPolicy);
    }

    private DynamicHttpToolExecutor executor(
            HttpToolSecretProvider secretProvider, HttpToolUrlPolicy urlPolicy) {
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

    private static void slowRedirect(HttpExchange exchange, String location, long delayMillis) throws IOException {
        try {
            Thread.sleep(delayMillis);
            redirect(exchange, 302, location);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondWithoutContentType(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
