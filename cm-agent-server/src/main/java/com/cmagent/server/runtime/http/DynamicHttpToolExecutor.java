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
     * 创建 {@code DynamicHttpToolExecutor} 实例并保存其运行所需依赖。
     *
     * @param properties 模块配置属性，用于读取运行参数。
     * @param secretProvider 按引用解析外部 Secret 的组件。
     * @param urlPolicy 执行协议、主机和地址安全检查的 URL 策略。
     * @param inputMapper 将工具输入映射为 HTTP 请求组成部分的组件。
     * @param objectMapper JSON 映射器，用于序列化或解析 JSON。
     * @param sanitizer 负责清理工具输出的安全组件。
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
     * 创建 {@code DynamicHttpToolExecutor} 实例并保存其运行所需依赖。
     *
     * @param properties 模块配置属性，用于读取运行参数。
     * @param secretProvider 按引用解析外部 Secret 的组件。
     * @param urlPolicy 执行协议、主机和地址安全检查的 URL 策略。
     * @param inputMapper 将工具输入映射为 HTTP 请求组成部分的组件。
     * @param objectMapper JSON 映射器，用于序列化或解析 JSON。
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
     * 创建 {@code DynamicHttpToolExecutor} 实例并保存其运行所需依赖。
     *
     * @param properties 模块配置属性，用于读取运行参数。
     * @param secretProvider 按引用解析外部 Secret 的组件。
     * @param urlPolicy 执行协议、主机和地址安全检查的 URL 策略。
     * @param inputMapper 将工具输入映射为 HTTP 请求组成部分的组件。
     * @param objectMapper JSON 映射器，用于序列化或解析 JSON。
     * @param httpTransport 实际发送 HTTP 请求的底层传输组件
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
     * 创建 {@code DynamicHttpToolExecutor} 实例并保存其运行所需依赖。
     *
     * @param properties 模块配置属性，用于读取运行参数。
     * @param secretProvider 按引用解析外部 Secret 的组件。
     * @param urlPolicy 执行协议、主机和地址安全检查的 URL 策略。
     * @param inputMapper 将工具输入映射为 HTTP 请求组成部分的组件。
     * @param objectMapper JSON 映射器，用于序列化或解析 JSON。
     * @param httpTransport 实际发送 HTTP 请求的底层传输组件
     * @param sanitizer 负责清理工具输出的安全组件。
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
     * 创建执行外部 HTTP 请求的受控传输实现。
     *
     * @param properties 模块配置属性，用于读取运行参数。
     */
    private static HttpTransport createTransport(HttpToolProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.getMaxTimeout())
                .build();
        return new HttpTransport() {
            @Override
            /**
             * 发送受治理的 HTTP 请求并返回受大小限制的响应。
             *
             * @param request 已完成地址和请求头安全校验的出站 HTTP 请求
             */
            public HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException {
                return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            }

            @Override
            /**
             * 关闭当前协议服务或底层传输资源。
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
     * @param config 当前工具对应的动态 HTTP 执行配置
     * @param executionRequest 包含租户、主体和工具输入的执行请求
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
        // Secret 解析、输入映射、地址校验和网络请求共享同一截止时间，避免分阶段超时叠加。
        Deadline deadline = Deadline.start(config.timeout());
        ResolvedHeaders secretHeaders;
        try {
            // Secret 仅在本次调用内解析；解析失败、取消或超时都不能继续构造出站请求。
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
            // 先按 Schema 校验输入并拆分到 PATH、QUERY、HEADER、BODY，再接触目标网络地址。
            JsonNode input = objectMapper.readTree(executionRequest.inputJson());
            prepared = inputMapper.map(config, input);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return ToolExecutionResult.failed("HTTP 工具输入无效", null);
        }
        if (deadline.expired()) {
            return ToolExecutionResult.failed("HTTP 请求超时", null);
        }

        // 动态请求头与 Secret 请求头必须无冲突合并，防止调用输入覆盖受保护凭据。
        Map<String, String> headers = mergeHeaders(prepared.headers(), secretHeaders.values());
        if (headers == null) {
            return ToolExecutionResult.failed("HTTP 请求头配置不安全", null);
        }

        URI initialUri;
        try {
            // 路径替换和查询编码完成后才生成最终 URI；每次发送前仍会再次执行网络策略校验。
            initialUri = buildUri(config.urlTemplate(), prepared);
        } catch (IllegalArgumentException exception) {
            return ToolExecutionResult.failed("HTTP 请求地址无效", null);
        }
        return send(config.method(), initialUri, prepared.body(), headers, deadline,
                secretHeaders.secretValues());
    }

    /**
     * 判断执行上下文中的工具和租户是否与当前配置一致。
     *
     * @param tool 当前处理的工具定义。
     * @param config 当前工具对应的动态 HTTP 执行配置
     * @param request 包含租户上下文和输入 JSON 的工具执行请求
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
     * 判断工具配置的超时是否位于服务端允许范围内。
     *
     * @param timeout 本次操作使用的超时限制。
     */
    private boolean isAllowedTimeout(Duration timeout) {
        return timeout != null && properties.getMinTimeout() != null && properties.getMaxTimeout() != null
                && timeout.compareTo(properties.getMinTimeout()) >= 0
                && timeout.compareTo(properties.getMaxTimeout()) <= 0;
    }

    /**
     * 解析配置中的 Secret 引用并生成待发送请求头。
     *
     * @param config 包含密钥请求头引用的动态 HTTP 工具配置
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
     * 合并静态请求头和 Secret 请求头，同时拒绝名称冲突。
     *
     * @param dynamic 参数映射产生的动态请求头
     * @param secrets 密钥提供器解析出的受保护请求头
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
     * 判断请求头名称是否允许由动态工具设置。
     *
     * @param name 目标对象的名称。
     */
    private boolean isSafeHeader(String name) {
        return name != null && HEADER_NAME.matcher(name).matches()
                && !FORBIDDEN_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * 判断请求头值是否不含换行等注入字符。
     *
     * @param value 待检查、转换或规范化的值。
     */
    private boolean isSafeHeaderValue(String value) {
        return value != null && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    /**
     * 将路径模板和查询参数组装为最终 URI。
     *
     * @param urlTemplate HTTP 工具配置的 URL 模板。
     * @param prepared 已完成治理校验的工具执行上下文。
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
     * 对 URL 组件执行 UTF-8 百分号编码。
     *
     * @param value 待检查、转换或规范化的值。
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
     * 发送受治理的 HTTP 请求并返回受大小限制的响应。
     *
     * @param initialMethod 重定向链开始时使用的 HTTP 方法
     * @param initialUri 重定向链开始时经过安全校验的 URI
     * @param body 待构造或发送的请求体。
     * @param headers 待发送或合并的 HTTP 请求头。
     * @param deadline 本次 HTTP 调用共享的截止时间。
     * @param secretValues 本次调用中临时使用的 Secret 值。
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
                // DNS 解析结果可能随重定向变化，因此每一跳都重新执行 SSRF 与目标地址策略校验。
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
                    // 遵循常见客户端语义：303 以及 POST 的 301/302 重定向改为无请求体的 GET。
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
                // 只接受单一且明确的编码和媒体类型，避免压缩炸弹或类型混淆绕过响应限制。
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
                // 多读取一个字节用于判断是否越界，并在超时时主动关闭响应流解除阻塞。
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
                        // JSON 必须完整解析且无尾随内容；返回前再统一清理敏感字段和 Secret 原值。
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
     * 在统一截止时间内执行可能阻塞的步骤。
     *
     * @param supplier 需要在限定时间内完成的异步取值操作
     * @param deadline 本次 HTTP 调用共享的截止时间。
     * @param timeoutCleanup 超时或取消后释放底层资源的清理动作
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
     * 根据方法、请求体和请求头构建 HTTP 请求。
     *
     * @param method 当前准备发送的 HTTP 方法
     * @param uri 待校验或请求的 URI。
     * @param body 待构造或发送的请求体。
     * @param headers 待发送或合并的 HTTP 请求头。
     * @param timeout 本次操作使用的超时限制。
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
     * 判断响应内容类型是否在允许集合内。
     *
     * @param value 待检查、转换或规范化的值。
     */
    private boolean isSupportedContentType(String value) {
        String mediaType = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mediaType.startsWith("text/") || "application/json".equals(mediaType)
                || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    }

    /**
     * 判断内容类型是否为 JSON 或 JSON 后缀类型。
     *
     * @param value 待检查、转换或规范化的值。
     */
    private boolean isJsonContentType(String value) {
        String mediaType = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return "application/json".equals(mediaType)
                || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    }

    /**
     * 关闭资源并忽略清理阶段的次要异常。
     *
     * @param body 待构造或发送的请求体。
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
     * 在 Bean 销毁时释放 HTTP 线程池和传输资源。
     */
    public void destroy() {
        close();
    }

    @Override
    /**
     * 关闭当前协议服务或底层传输资源。
     */
    public void close() {
        httpTransport.close();
        blockingExecutor.shutdownNow();
    }

    /**
     * 封装 {@code ResolvedHeaders} 在 HTTP 工具流程中使用的不可变数据。
     */
    private record ResolvedHeaders(
            Map<String, String> values,
            List<String> secretValues,
            ToolExecutionResult failure,
            boolean cancelled
    ) {
        /**
         * 创建 HTTP 工具调用失败结果。
         *
         * @param failure 当前捕获的失败或异常。
         */
        private static ResolvedHeaders failed(ToolExecutionResult failure) {
            return new ResolvedHeaders(Map.of(), List.of(), failure, false);
        }

        /**
         * 创建因调用取消而失败的工具结果。
         */
        private static ResolvedHeaders cancelledResult() {
            return new ResolvedHeaders(Map.of(), List.of(), null, true);
        }
    }

    @FunctionalInterface
    /**
     * 定义 {@code HttpTransport} 的内部协作契约。
     */
    interface HttpTransport extends AutoCloseable {
        /**
         * 发送受治理的 HTTP 请求并返回受大小限制的响应。
         *
         * @param request 已完成地址和请求头安全校验的出站 HTTP 请求
         */
        HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;

        @Override
        /**
         * 关闭当前协议服务或底层传输资源。
         */
        default void close() {
            // 测试传输默认没有独立资源，生产传输会覆盖并关闭 HttpClient。
        }
    }

    @FunctionalInterface
    /**
     * 定义 {@code CheckedSupplier} 的内部协作契约。
     */
    private interface CheckedSupplier<T> {
        /**
         * 执行可能抛出受检异常的取值操作，供统一异常转换逻辑调用。
         */
        T get() throws Exception;
    }

    /**
     * 封装 {@code Deadline} 在 HTTP 工具流程中使用的不可变数据。
     */
    private record Deadline(long deadlineNanos) {
        /**
         * 创建从当前时刻开始计时的截止时间。
         *
         * @param timeout 本次操作使用的超时限制。
         */
        private static Deadline start(Duration timeout) {
            long now = System.nanoTime();
            long durationNanos = timeout.toNanos();
            long deadline = durationNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + durationNanos;
            return new Deadline(deadline);
        }

        /**
         * 计算截止时间前剩余的纳秒数。
         */
        private long remainingNanos() {
            return deadlineNanos - System.nanoTime();
        }

        /**
         * 计算截止时间前剩余的时长。
         */
        private Duration remaining() throws HttpTimeoutException {
            long nanos = remainingNanos();
            if (nanos <= 0) {
                throw new HttpTimeoutException("HTTP deadline exceeded");
            }
            return Duration.ofNanos(nanos);
        }

        /**
         * 判断截止时间是否已到达。
         */
        private boolean expired() {
            return remainingNanos() <= 0;
        }
    }
}
