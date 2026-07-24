package com.cmagent.server.runtime.http;

import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.server.security.ToolOutputSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
/** 执行动态 HTTP 工具，负责请求构造、超时、响应限制和资源释放。 */
public class DynamicHttpToolExecutor implements DisposableBean, AutoCloseable {
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> FORBIDDEN_REQUEST_HEADERS = Set.of(
            "host", "content-length", "content-type", "accept-encoding", "connection", "transfer-encoding",
            "proxy-authorization", "upgrade"
    );
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    private final HttpToolProperties properties;
    private final HttpToolSecretProvider secretProvider;
    private final HttpToolUrlPolicy urlPolicy;
    private final HttpToolInputMapper inputMapper;
    private final ObjectMapper objectMapper;
    private final ToolOutputSanitizer sanitizer;
    private final HttpTransport httpTransport;
    private final ExecutorService blockingExecutor;

    @Autowired
    /**
     * DynamicHttpToolExecutor：处理该类内部的业务逻辑或辅助计算。
     */
    public DynamicHttpToolExecutor(
            HttpToolProperties properties,
            HttpToolSecretProvider secretProvider,
            HttpToolUrlPolicy urlPolicy,
            HttpToolInputMapper inputMapper,
            ObjectMapper objectMapper,
            ToolOutputSanitizer sanitizer
    ) {
        this(properties, secretProvider, urlPolicy, inputMapper, objectMapper, createTransport(properties), sanitizer);
    }
    /**
     * DynamicHttpToolExecutor：处理该类内部的业务逻辑或辅助计算。
     */
    public DynamicHttpToolExecutor(
            HttpToolProperties properties,
            HttpToolSecretProvider secretProvider,
            HttpToolUrlPolicy urlPolicy,
            HttpToolInputMapper inputMapper,
            ObjectMapper objectMapper
    ) {
        this(properties, secretProvider, urlPolicy, inputMapper, objectMapper, createTransport(properties),
                new ToolOutputSanitizer(objectMapper));
    }
    /**
     * DynamicHttpToolExecutor：处理该类内部的业务逻辑或辅助计算。
     */
    DynamicHttpToolExecutor(
            HttpToolProperties properties,
            HttpToolSecretProvider secretProvider,
            HttpToolUrlPolicy urlPolicy,
            HttpToolInputMapper inputMapper,
            ObjectMapper objectMapper,
            HttpTransport httpTransport
    ) {
        this(properties, secretProvider, urlPolicy, inputMapper, objectMapper, httpTransport,
                new ToolOutputSanitizer(objectMapper));
    }
    /**
     * DynamicHttpToolExecutor：处理该类内部的业务逻辑或辅助计算。
     */
    DynamicHttpToolExecutor(
            HttpToolProperties properties,
            HttpToolSecretProvider secretProvider,
            HttpToolUrlPolicy urlPolicy,
            HttpToolInputMapper inputMapper,
            ObjectMapper objectMapper,
            HttpTransport httpTransport,
            ToolOutputSanitizer sanitizer
    ) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.secretProvider = Objects.requireNonNull(secretProvider, "secretProvider 不能为空");
        this.urlPolicy = Objects.requireNonNull(urlPolicy, "urlPolicy 不能为空");
        this.inputMapper = Objects.requireNonNull(inputMapper, "inputMapper 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer 不能为空");
        this.httpTransport = Objects.requireNonNull(httpTransport, "httpTransport 不能为空");
        this.blockingExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("cm-agent-http-", 0).factory());
    }

    /**
     * createTransport：创建并返回新的领域对象或配置。
     */
    private static HttpTransport createTransport(HttpToolProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.getMaxTimeout())
                .build();
        return new HttpTransport() {
            @Override
            /**
             * send：处理该类内部的业务逻辑或辅助计算。
             */
            public HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException {
                return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            }

            @Override
            /**
             * close：处理该类内部的业务逻辑或辅助计算。
             */
            public void close() {
                client.shutdownNow();
            }
        };
    }

    /**
     * 执行动态 HTTP 工具请求，并限制目标地址、重定向、超时和响应大小。
     *
     * @param tool    工具定义
     * @param request 工具调用请求
     * @return HTTP 响应映射成的工具执行结果
     * @throws RuntimeException 请求构造、网络访问或响应处理失败时抛出
     */
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
        Deadline deadline = Deadline.start(config.timeout());
        ResolvedHeaders secretHeaders;
        try {
            secretHeaders = runWithinDeadline(() -> resolveSecretHeaders(config), deadline, () -> {
            });
        } catch (HttpTimeoutException exception) {
            return ToolExecutionResult.failed("HTTP 工具调用超时", null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.failed("HTTP 请求中断", null);
        } catch (Exception exception) {
            return ToolExecutionResult.failed("HTTP Secret 不可用", null);
        }
        if (secretHeaders.cancelled()) {
            return ToolExecutionResult.failed("HTTP 请求中断", null);
        }
        if (secretHeaders.failure() != null) {
            return secretHeaders.failure();
        }
        if (deadline.expired()) {
            return ToolExecutionResult.failed("HTTP 工具调用超时", null);
        }

        PreparedHttpToolRequest prepared;
        try {
            JsonNode input = objectMapper.readTree(executionRequest.inputJson());
            prepared = inputMapper.map(config, input);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return ToolExecutionResult.failed("HTTP 工具输入无效", null);
        }
        if (deadline.expired()) {
            return ToolExecutionResult.failed("HTTP 请求超时", null);
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
        return send(config.method(), initialUri, prepared.body(), headers, deadline,
                secretHeaders.secretValues());
    }

    /**
     * isMatchingContext：判断当前条件是否成立。
     */
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

    /**
     * isAllowedTimeout：判断当前条件是否成立。
     */
    private boolean isAllowedTimeout(Duration timeout) {
        return timeout != null && properties.getMinTimeout() != null && properties.getMaxTimeout() != null
                && timeout.compareTo(properties.getMinTimeout()) >= 0
                && timeout.compareTo(properties.getMaxTimeout()) <= 0;
    }

    /**
     * resolveSecretHeaders：解析并定位可用的目标对象。
     */
    private ResolvedHeaders resolveSecretHeaders(HttpToolConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        List<String> secretValues = new ArrayList<>();
        Set<String> normalizedNames = new HashSet<>();
        for (Map.Entry<String, String> entry : config.secretHeaders().entrySet()) {
            String name = entry.getKey();
            if (!isSafeHeader(name) || !normalizedNames.add(name.toLowerCase(Locale.ROOT))) {
                return ResolvedHeaders.failed(ToolExecutionResult.failed("HTTP 请求头配置不安全", null));
            }
            if (Thread.currentThread().isInterrupted()) {
                return ResolvedHeaders.cancelledResult();
            }
            String value;
            try {
                value = secretProvider.resolve(config.tenantId(), entry.getValue()).orElse(null);
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    return ResolvedHeaders.cancelledResult();
                }
                return ResolvedHeaders.failed(ToolExecutionResult.failed("HTTP Secret 不可用", null));
            }
            if (Thread.currentThread().isInterrupted()) {
                return ResolvedHeaders.cancelledResult();
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
        return new ResolvedHeaders(Map.copyOf(headers), List.copyOf(secretValues), null, false);
    }

    /**
     * mergeHeaders：处理该类内部的业务逻辑或辅助计算。
     */
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

    /**
     * isSafeHeader：判断当前条件是否成立。
     */
    private boolean isSafeHeader(String name) {
        return name != null && HEADER_NAME.matcher(name).matches()
                && !FORBIDDEN_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * isSafeHeaderValue：判断当前条件是否成立。
     */
    private boolean isSafeHeaderValue(String value) {
        return value != null && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    /**
     * buildUri：处理该类内部的业务逻辑或辅助计算。
     */
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

    /**
     * encode：转换并生成规范化输出。
     */
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

    /**
     * send：处理该类内部的业务逻辑或辅助计算。
     */
    private ToolExecutionResult send(
            HttpToolMethod initialMethod,
            URI initialUri,
            JsonNode body,
            Map<String, String> headers,
            Deadline deadline,
            List<String> secretValues
    ) {
        HttpToolMethod method = initialMethod;
        URI currentUri = initialUri;
        JsonNode currentBody = body;
        int redirects = 0;
        while (true) {
            try {
                URI uriToValidate = currentUri;
                currentUri = runWithinDeadline(() -> urlPolicy.validate(uriToValidate), deadline, () -> {
                });
                HttpRequest request = buildRequest(method, currentUri, currentBody, headers, deadline.remaining());
                HttpResponse<InputStream> response = runWithinDeadline(
                        () -> httpTransport.send(request), deadline, () -> {
                        });
                int status = response.statusCode();
                if (REDIRECT_STATUSES.contains(status)) {
                    closeQuietly(response.body());
                    if (redirects >= properties.getMaxRedirects()) {
                        return ToolExecutionResult.failed("HTTP 重定向次数超限", status);
                    }
                    List<String> locations = response.headers().allValues("Location");
                    if (locations.size() != 1 || locations.getFirst().isBlank()) {
                        return ToolExecutionResult.failed("HTTP 重定向响应头无效", status);
                    }
                    String location = locations.getFirst();
                    try {
                        URI redirectUri = currentUri.resolve(URI.create(location));
                        if (!urlPolicy.hasSameOrigin(currentUri, redirectUri)) {
                            return ToolExecutionResult.failed("HTTP 重定向跨源被拒绝", status);
                        }
                        currentUri = redirectUri;
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
                List<String> contentEncodings = response.headers().allValues("Content-Encoding");
                if (contentEncodings.size() > 1 || (contentEncodings.size() == 1
                        && !"identity".equalsIgnoreCase(contentEncodings.getFirst().trim()))) {
                    closeQuietly(response.body());
                    return ToolExecutionResult.failed("HTTP 响应编码不受支持", status);
                }
                List<String> contentTypes = response.headers().allValues("Content-Type");
                if (contentTypes.size() != 1) {
                    closeQuietly(response.body());
                    return ToolExecutionResult.failed("HTTP 响应头不安全", status);
                }
                String contentType = contentTypes.getFirst();
                if (!isSupportedContentType(contentType)) {
                    closeQuietly(response.body());
                    return ToolExecutionResult.failed("HTTP 响应类型不受支持", status);
                }
                byte[] bytes;
                InputStream stream = response.body();
                bytes = runWithinDeadline(() -> {
                    try (stream) {
                        return stream.readNBytes(properties.getMaxResponseBytes() + 1);
                    }
                }, deadline, () -> closeQuietly(stream));
                if (bytes.length > properties.getMaxResponseBytes()) {
                    return ToolExecutionResult.failed("HTTP 响应超过大小限制", status);
                }
                String decoded = new String(bytes, StandardCharsets.UTF_8);
                String output;
                if (isJsonContentType(contentType)) {
                    try {
                        JsonNode json = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(decoded);
                        if (json == null || json.isMissingNode()) {
                            return ToolExecutionResult.failed("HTTP JSON 响应无效", status);
                        }
                        output = sanitizer.sanitize(decoded, secretValues);
                    } catch (JsonProcessingException exception) {
                        return ToolExecutionResult.failed("HTTP JSON 响应无效", status);
                    }
                } else {
                    output = sanitizer.sanitize(decoded, secretValues);
                }
                if (sanitizer.exceedsByteLimit(output, properties.getMaxResponseBytes())) {
                    return ToolExecutionResult.failed("HTTP 响应超过大小限制", status);
                }
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
            } catch (Exception exception) {
                return ToolExecutionResult.failed("HTTP 连接失败", null);
            }
        }
    }

    /**
     * runWithinDeadline：执行当前流程并返回处理结果。
     */
    private <T> T runWithinDeadline(
            CheckedSupplier<T> supplier,
            Deadline deadline,
            Runnable timeoutCleanup
    ) throws Exception {
        long remainingNanos = deadline.remainingNanos();
        if (remainingNanos <= 0) {
            timeoutCleanup.run();
            throw new HttpTimeoutException("HTTP deadline exceeded");
        }
        Future<T> future = blockingExecutor.submit(supplier::get);
        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            timeoutCleanup.run();
            future.cancel(true);
            throw new HttpTimeoutException("HTTP deadline exceeded");
        } catch (InterruptedException exception) {
            timeoutCleanup.run();
            future.cancel(true);
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("HTTP 执行失败");
        }
    }

    /**
     * buildRequest：处理该类内部的业务逻辑或辅助计算。
     */
    private HttpRequest buildRequest(
            HttpToolMethod method,
            URI uri,
            JsonNode body,
            Map<String, String> headers,
            Duration timeout
    ) throws JsonProcessingException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
        builder.header("Accept-Encoding", "identity");
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

    /**
     * isSupportedContentType：判断当前条件是否成立。
     */
    private boolean isSupportedContentType(String value) {
        String mediaType = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mediaType.startsWith("text/") || "application/json".equals(mediaType)
                || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    }

    /**
     * isJsonContentType：判断当前条件是否成立。
     */
    private boolean isJsonContentType(String value) {
        String mediaType = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return "application/json".equals(mediaType)
                || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    }

    /**
     * closeQuietly：处理该类内部的业务逻辑或辅助计算。
     */
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

    @Override
    /**
     * destroy：处理该类内部的业务逻辑或辅助计算。
     */
    public void destroy() {
        close();
    }

    @Override
    /**
     * close：处理该类内部的业务逻辑或辅助计算。
     */
    public void close() {
        httpTransport.close();
        blockingExecutor.shutdownNow();
    }

    /**
     * ResolvedHeaders：不可变数据载体，用于在本模块内传递结构化信息。
     */
    private record ResolvedHeaders(
            Map<String, String> values,
            List<String> secretValues,
            ToolExecutionResult failure,
            boolean cancelled
    ) {
        /**
         * failed：处理该类内部的业务逻辑或辅助计算。
         */
        private static ResolvedHeaders failed(ToolExecutionResult failure) {
            return new ResolvedHeaders(Map.of(), List.of(), failure, false);
        }

        /**
         * cancelledResult：处理该类内部的业务逻辑或辅助计算。
         */
        private static ResolvedHeaders cancelledResult() {
            return new ResolvedHeaders(Map.of(), List.of(), null, true);
        }
    }

    @FunctionalInterface
    /**
     * HttpTransport：定义本模块使用的协作契约。
     */
    interface HttpTransport extends AutoCloseable {
        /**
         * send：处理该类内部的业务逻辑或辅助计算。
         */
        HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;

        @Override
        /**
         * close：处理该类内部的业务逻辑或辅助计算。
         */
        default void close() {
            // 测试传输默认没有独立资源，生产传输会覆盖并关闭 HttpClient。
        }
    }

    @FunctionalInterface
    /**
     * CheckedSupplier：定义本模块使用的协作契约。
     */
    private interface CheckedSupplier<T> {
        /**
         * get：返回当前配置或状态。
         */
        T get() throws Exception;
    }

    /**
     * Deadline：不可变数据载体，用于在本模块内传递结构化信息。
     */
    private record Deadline(long deadlineNanos) {
        /**
         * start：处理该类内部的业务逻辑或辅助计算。
         */
        private static Deadline start(Duration timeout) {
            long now = System.nanoTime();
            long durationNanos = timeout.toNanos();
            long deadline = durationNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + durationNanos;
            return new Deadline(deadline);
        }

        /**
         * remainingNanos：处理该类内部的业务逻辑或辅助计算。
         */
        private long remainingNanos() {
            return deadlineNanos - System.nanoTime();
        }

        /**
         * remaining：处理该类内部的业务逻辑或辅助计算。
         */
        private Duration remaining() throws HttpTimeoutException {
            long nanos = remainingNanos();
            if (nanos <= 0) {
                throw new HttpTimeoutException("HTTP deadline exceeded");
            }
            return Duration.ofNanos(nanos);
        }

        /**
         * expired：处理该类内部的业务逻辑或辅助计算。
         */
        private boolean expired() {
            return remainingNanos() <= 0;
        }
    }
}
