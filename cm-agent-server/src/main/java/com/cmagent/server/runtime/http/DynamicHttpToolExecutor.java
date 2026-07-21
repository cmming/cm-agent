package com.cmagent.server.runtime.http;

import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class DynamicHttpToolExecutor {
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> FORBIDDEN_REQUEST_HEADERS = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "proxy-authorization", "upgrade"
    );
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s\\\"'<>]+");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[^\\s\\\"',;}]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|api[-_]?key|jwt[-_]?secret|secret|token)\\s*[:=]\\s*" +
                    "(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}]*)"
    );
    private static final Pattern SENSITIVE_HEADER = Pattern.compile(
            "(?im)^(?:authorization|cookie|set-cookie)\\s*[:=][^\\r\\n]*$"
    );

    private final HttpToolProperties properties;
    private final HttpToolSecretProvider secretProvider;
    private final HttpToolUrlPolicy urlPolicy;
    private final HttpToolInputMapper inputMapper;
    private final ObjectMapper objectMapper;
    private final HttpTransport httpTransport;

    @Autowired
    public DynamicHttpToolExecutor(
            HttpToolProperties properties,
            HttpToolSecretProvider secretProvider,
            HttpToolUrlPolicy urlPolicy,
            HttpToolInputMapper inputMapper,
            ObjectMapper objectMapper
    ) {
        this(properties, secretProvider, urlPolicy, inputMapper, objectMapper, createTransport(properties));
    }

    DynamicHttpToolExecutor(
            HttpToolProperties properties,
            HttpToolSecretProvider secretProvider,
            HttpToolUrlPolicy urlPolicy,
            HttpToolInputMapper inputMapper,
            ObjectMapper objectMapper,
            HttpTransport httpTransport
    ) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.secretProvider = Objects.requireNonNull(secretProvider, "secretProvider 不能为空");
        this.urlPolicy = Objects.requireNonNull(urlPolicy, "urlPolicy 不能为空");
        this.inputMapper = Objects.requireNonNull(inputMapper, "inputMapper 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.httpTransport = Objects.requireNonNull(httpTransport, "httpTransport 不能为空");
    }

    private static HttpTransport createTransport(HttpToolProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.getMaxTimeout())
                .build();
        return request -> client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    public ToolExecutionResult execute(
            ToolDefinition tool,
            HttpToolConfig config,
            ToolExecutionRequest executionRequest
    ) {
        if (!properties.isEnabled()) {
            return ToolExecutionResult.failed("HTTP 工具未启用", null);
        }
        if (!isMatchingContext(tool, config, executionRequest)) {
            return ToolExecutionResult.failed("工具不可用", null);
        }
        if (!isAllowedTimeout(config.timeout())) {
            return ToolExecutionResult.failed("HTTP 超时配置不允许", null);
        }
        ResolvedHeaders secretHeaders = resolveSecretHeaders(config);
        if (secretHeaders.failure() != null) {
            return secretHeaders.failure();
        }

        PreparedHttpToolRequest prepared;
        try {
            JsonNode input = objectMapper.readTree(executionRequest.inputJson());
            prepared = inputMapper.map(config, input);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return ToolExecutionResult.failed("HTTP 工具输入无效", null);
        }

        Map<String, String> headers = mergeHeaders(prepared.headers(), secretHeaders.values());
        if (headers == null) {
            return ToolExecutionResult.failed("HTTP 请求头配置不安全", null);
        }

        URI initialUri;
        try {
            initialUri = buildUri(config.urlTemplate(), prepared);
        } catch (IllegalArgumentException exception) {
            return ToolExecutionResult.failed("HTTP 请求地址无效", null);
        }
        return send(config.method(), initialUri, prepared.body(), headers, config.timeout(),
                secretHeaders.secretValues());
    }

    private boolean isMatchingContext(
            ToolDefinition tool,
            HttpToolConfig config,
            ToolExecutionRequest request
    ) {
        return tool != null && config != null && request != null
                && tool.id().equals(config.toolId()) && tool.tenantId().equals(config.tenantId())
                && request.toolId().equals(config.toolId())
                && (request.tenantId() == null || request.tenantId().equals(config.tenantId()));
    }

    private boolean isAllowedTimeout(Duration timeout) {
        return timeout != null && properties.getMinTimeout() != null && properties.getMaxTimeout() != null
                && timeout.compareTo(properties.getMinTimeout()) >= 0
                && timeout.compareTo(properties.getMaxTimeout()) <= 0;
    }

    private ResolvedHeaders resolveSecretHeaders(HttpToolConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        List<String> secretValues = new ArrayList<>();
        Set<String> normalizedNames = new HashSet<>();
        for (Map.Entry<String, String> entry : config.secretHeaders().entrySet()) {
            String name = entry.getKey();
            if (!isSafeHeader(name) || !normalizedNames.add(name.toLowerCase(Locale.ROOT))) {
                return ResolvedHeaders.failed(ToolExecutionResult.failed("HTTP 请求头配置不安全", null));
            }
            String value;
            try {
                value = secretProvider.resolve(config.tenantId(), entry.getValue()).orElse(null);
            } catch (RuntimeException exception) {
                return ResolvedHeaders.failed(ToolExecutionResult.failed("HTTP Secret 不可用", null));
            }
            if (value == null || value.isBlank()) {
                return ResolvedHeaders.failed(ToolExecutionResult.failed("HTTP Secret 不可用", null));
            }
            if (!isSafeHeaderValue(value)) {
                return ResolvedHeaders.failed(ToolExecutionResult.failed("HTTP 请求头配置不安全", null));
            }
            headers.put(name, value);
            secretValues.add(value);
        }
        return new ResolvedHeaders(Map.copyOf(headers), List.copyOf(secretValues), null);
    }

    private Map<String, String> mergeHeaders(Map<String, String> dynamic, Map<String, String> secrets) {
        Map<String, String> merged = new LinkedHashMap<>();
        Set<String> normalizedNames = new HashSet<>();
        for (Map<String, String> source : List.of(dynamic, secrets)) {
            for (Map.Entry<String, String> entry : source.entrySet()) {
                String normalized = entry.getKey().toLowerCase(Locale.ROOT);
                if (!isSafeHeader(entry.getKey()) || !isSafeHeaderValue(entry.getValue())
                        || !normalizedNames.add(normalized)) {
                    return null;
                }
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(merged);
    }

    private boolean isSafeHeader(String name) {
        return name != null && HEADER_NAME.matcher(name).matches()
                && !FORBIDDEN_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    private boolean isSafeHeaderValue(String value) {
        return value != null && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private URI buildUri(String urlTemplate, PreparedHttpToolRequest prepared) {
        String expanded = urlTemplate;
        for (Map.Entry<String, String> entry : prepared.pathValues().entrySet()) {
            expanded = expanded.replace("{" + entry.getKey() + "}", encode(entry.getValue()));
        }
        if (expanded.indexOf('{') >= 0 || expanded.indexOf('}') >= 0) {
            throw new IllegalArgumentException("URL 占位符未完整替换");
        }
        URI base = URI.create(expanded);
        if (prepared.queryValues().isEmpty()) {
            return base;
        }
        StringBuilder value = new StringBuilder(base.toASCIIString());
        value.append(base.getRawQuery() == null ? '?' : '&');
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : prepared.queryValues().entrySet()) {
            for (String item : entry.getValue()) {
                if (!first) {
                    value.append('&');
                }
                value.append(encode(entry.getKey())).append('=').append(encode(item));
                first = false;
            }
        }
        return URI.create(value.toString());
    }

    private String encode(String value) {
        char[] hex = "0123456789ABCDEF".toCharArray();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length * 3);
        for (byte current : bytes) {
            int unsigned = current & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z') || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9') || unsigned == '-' || unsigned == '.'
                    || unsigned == '_' || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(hex[unsigned >>> 4]);
                encoded.append(hex[unsigned & 0x0f]);
            }
        }
        return encoded.toString();
    }

    private ToolExecutionResult send(
            HttpToolMethod initialMethod,
            URI initialUri,
            JsonNode body,
            Map<String, String> headers,
            Duration timeout,
            List<String> secretValues
    ) {
        HttpToolMethod method = initialMethod;
        URI currentUri = initialUri;
        JsonNode currentBody = body;
        int redirects = 0;
        while (true) {
            try {
                currentUri = urlPolicy.validate(currentUri);
                HttpRequest request = buildRequest(method, currentUri, currentBody, headers, timeout);
                HttpResponse<InputStream> response = httpTransport.send(request);
                int status = response.statusCode();
                if (REDIRECT_STATUSES.contains(status)) {
                    closeQuietly(response.body());
                    if (redirects >= properties.getMaxRedirects()) {
                        return ToolExecutionResult.failed("HTTP 重定向次数超限", status);
                    }
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location == null || location.isBlank()) {
                        return ToolExecutionResult.failed("HTTP 重定向地址无效", status);
                    }
                    try {
                        currentUri = currentUri.resolve(URI.create(location));
                    } catch (IllegalArgumentException exception) {
                        return ToolExecutionResult.failed("HTTP 重定向地址无效", status);
                    }
                    redirects++;
                    if (status == 303 || ((status == 301 || status == 302) && method == HttpToolMethod.POST)) {
                        method = HttpToolMethod.GET;
                        currentBody = null;
                    }
                    continue;
                }
                if (status < 200 || status >= 300) {
                    closeQuietly(response.body());
                    return ToolExecutionResult.failed("HTTP 服务返回非成功状态", status);
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!isSupportedContentType(contentType)) {
                    closeQuietly(response.body());
                    return ToolExecutionResult.failed("HTTP 响应类型不受支持", status);
                }
                byte[] bytes;
                try (InputStream stream = response.body()) {
                    bytes = stream.readNBytes(properties.getMaxResponseBytes() + 1);
                }
                if (bytes.length > properties.getMaxResponseBytes()) {
                    return ToolExecutionResult.failed("HTTP 响应超过大小限制", status);
                }
                String output = redact(new String(bytes, StandardCharsets.UTF_8), secretValues);
                return ToolExecutionResult.succeeded(output, status);
            } catch (IllegalArgumentException exception) {
                return ToolExecutionResult.failed("HTTP 目标地址不允许", null);
            } catch (HttpTimeoutException exception) {
                return ToolExecutionResult.failed("HTTP 请求超时", null);
            } catch (SSLException exception) {
                return ToolExecutionResult.failed("HTTP TLS 连接失败", null);
            } catch (ConnectException exception) {
                return ToolExecutionResult.failed("HTTP 连接失败", null);
            } catch (IOException exception) {
                return ToolExecutionResult.failed("HTTP 连接失败", null);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return ToolExecutionResult.failed("HTTP 请求中断", null);
            }
        }
    }

    private HttpRequest buildRequest(
            HttpToolMethod method,
            URI uri,
            JsonNode body,
            Map<String, String> headers,
            Duration timeout
    ) throws JsonProcessingException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(builder::header);
        if (method == HttpToolMethod.POST) {
            String json = body == null || body.isNull() ? "{}" : objectMapper.writeValueAsString(body);
            builder.header("Content-Type", "application/json; charset=utf-8");
            builder.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private boolean isSupportedContentType(String value) {
        String mediaType = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mediaType.startsWith("text/") || "application/json".equals(mediaType)
                || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    }

    private String redact(String value, List<String> secretValues) {
        String redacted = value;
        for (String secret : secretValues) {
            redacted = redacted.replace(secret, "<已脱敏>");
        }
        redacted = URL.matcher(redacted).replaceAll("<已脱敏URL>");
        redacted = SENSITIVE_HEADER.matcher(redacted).replaceAll("<已脱敏>");
        redacted = BEARER.matcher(redacted).replaceAll("Bearer <已脱敏>");
        return SECRET_ASSIGNMENT.matcher(redacted).replaceAll("$1=<已脱敏>");
    }

    private static void closeQuietly(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException ignored) {
            // 响应已被拒绝，关闭失败不覆盖固定安全错误语义。
        }
    }

    private record ResolvedHeaders(
            Map<String, String> values,
            List<String> secretValues,
            ToolExecutionResult failure
    ) {
        private static ResolvedHeaders failed(ToolExecutionResult failure) {
            return new ResolvedHeaders(Map.of(), List.of(), failure);
        }
    }

    @FunctionalInterface
    interface HttpTransport {
        HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
    }
}
