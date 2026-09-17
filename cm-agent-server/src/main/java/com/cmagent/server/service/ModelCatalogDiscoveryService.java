package com.cmagent.server.service;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ModelCredentialProvider;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.ModelCatalogDiscoveryProperties;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.runtime.http.HttpToolUrlPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 在服务端受控地读取模型供应商目录，并将结果收敛为可保存的模型名称。
 *
 * <p>浏览器永远不会直接访问供应商。草稿路径只使用本次请求中的临时 API Key；已保存路径只按
 * 当前认证租户读取目标配置及其密文，因此不存在“修改地址后用旧密钥请求新主机”的外发通道。</p>
 */
@Service
public class ModelCatalogDiscoveryService {
    private static final String RESOURCE_TYPE = "MODEL_CONFIG";
    private static final String EVENT_TYPE = "MODEL_CONFIG_MODEL_DISCOVERY";
    private static final String SOURCE = "MODEL_CATALOG_DISCOVERY";

    private final ModelConfigRepository repository;
    private final @Nullable ModelCredentialProvider credentialProvider;
    private final AuditAppender auditAppender;
    private final ModelCatalogDiscoveryProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpToolUrlPolicy urlPolicy;
    private final CatalogTransport transport;

    @Autowired
    public ModelCatalogDiscoveryService(
            ModelConfigRepository repository,
            @Nullable ModelCredentialProvider credentialProvider,
            AuditAppender auditAppender,
            ModelCatalogDiscoveryProperties properties,
            ObjectMapper objectMapper
    ) {
        this(repository, credentialProvider, auditAppender, properties, objectMapper,
                new HttpToolUrlPolicy(properties.toUrlPolicyProperties()), createTransport(properties));
    }

    ModelCatalogDiscoveryService(
            ModelConfigRepository repository,
            @Nullable ModelCredentialProvider credentialProvider,
            AuditAppender auditAppender,
            ModelCatalogDiscoveryProperties properties,
            ObjectMapper objectMapper,
            HttpToolUrlPolicy urlPolicy,
            CatalogTransport transport
    ) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.credentialProvider = credentialProvider;
        this.auditAppender = Objects.requireNonNull(auditAppender, "auditAppender 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.urlPolicy = Objects.requireNonNull(urlPolicy, "urlPolicy 不能为空");
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
    }

    /**
     * 使用尚未保存的表单信息获取模型目录。
     *
     * @param principal 当前认证主体，提供可信 tenant 与审计主体
     * @param providerType 供应商协议类型
     * @param baseUrl 服务基础地址
     * @param apiKey 本次请求临时携带的 API Key，不会被保存或写入日志
     * @param errorId 当前 HTTP 请求的关联编号
     * @return 已去重、排序并限制数量的模型名称
     */
    public List<String> discoverDraft(
            PrincipalRef principal,
            ModelProviderType providerType,
            String baseUrl,
            String apiKey,
            String errorId
    ) {
        return discover(principal, "draft", providerType, baseUrl, apiKey, errorId);
    }

    /**
     * 使用当前租户内已保存的配置和密文获取模型目录。
     *
     * <p>该方法不接受客户端传入的地址或 Provider，避免调用方把已保存 API Key 重定向到其他目标。</p>
     *
     * @param principal 当前认证主体，提供可信 tenant 与审计主体
     * @param modelConfigId 已保存模型配置标识
     * @param errorId 当前 HTTP 请求的关联编号
     * @return 已去重、排序并限制数量的模型名称
     */
    public List<String> discoverSaved(PrincipalRef principal, java.util.UUID modelConfigId, String errorId) {
        ModelConfig config = repository.findByTenantAndId(principal.tenantId(), modelConfigId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "模型配置不存在"));
        if (credentialProvider == null) {
            auditFailure(principal, modelConfigId.toString(), ApiErrorCode.MODEL_DISCOVERY_AUTH_FAILED);
            throw failure(principal, modelConfigId.toString(), errorId, ApiErrorCode.MODEL_DISCOVERY_AUTH_FAILED,
                    HttpStatus.BAD_GATEWAY, "当前持久化模式不支持读取已保存的模型凭据，请重新填写 API Key",
                    new IllegalStateException("ModelCredentialProvider 未配置"));
        }
        ModelCredential credential;
        try {
            credential = credentialProvider.resolve(principal.tenantId(), modelConfigId);
        } catch (RuntimeException exception) {
            auditFailure(principal, modelConfigId.toString(), ApiErrorCode.MODEL_DISCOVERY_AUTH_FAILED);
            throw failure(principal, modelConfigId.toString(), errorId, ApiErrorCode.MODEL_DISCOVERY_AUTH_FAILED,
                    HttpStatus.BAD_GATEWAY, "已保存的模型凭据不可用，请重新填写 API Key", exception);
        }
        return discover(principal, modelConfigId.toString(), config.providerType(), config.baseUrl(), credential.apiKey(), errorId);
    }

    private List<String> discover(
            PrincipalRef principal,
            String resourceId,
            ModelProviderType providerType,
            String baseUrl,
            String apiKey,
            String errorId
    ) {
        Objects.requireNonNull(principal, "principal 不能为空");
        try {
            URI endpoint = urlPolicy.validate(modelsEndpoint(baseUrl));
            CatalogHttpResponse response = transport.get(endpoint, apiKey, properties.getTimeout(), properties.getMaxResponseBytes());
            List<String> names = parseResponse(providerType, response);
            auditAppender.append(principal.tenantId(), principal.principalId(), EVENT_TYPE, RESOURCE_TYPE, resourceId,
                    "SUCCEEDED", "已获取模型目录，共 " + names.size() + " 项");
            return names;
        } catch (CatalogFailure failure) {
            auditFailure(principal, resourceId, failure.errorCode());
            throw failure(principal, resourceId, errorId, failure.errorCode(), failure.status(), failure.getMessage(),
                    failure.getCause(), failure.upstreamResponse());
        } catch (IllegalArgumentException exception) {
            auditFailure(principal, resourceId, ApiErrorCode.MODEL_DISCOVERY_TARGET_REJECTED);
            throw failure(principal, resourceId, errorId, ApiErrorCode.MODEL_DISCOVERY_TARGET_REJECTED,
                    HttpStatus.BAD_REQUEST, "模型服务地址不允许访问", exception);
        } catch (RuntimeException exception) {
            auditFailure(principal, resourceId, ApiErrorCode.MODEL_DISCOVERY_UPSTREAM_ERROR);
            throw failure(principal, resourceId, errorId, ApiErrorCode.MODEL_DISCOVERY_UPSTREAM_ERROR,
                    HttpStatus.BAD_GATEWAY, "模型目录获取失败，请稍后重试或手动填写模型名称", exception);
        }
    }

    private List<String> parseResponse(ModelProviderType providerType, CatalogHttpResponse response) {
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new CatalogFailure(ApiErrorCode.MODEL_DISCOVERY_AUTH_FAILED, HttpStatus.BAD_GATEWAY,
                    "模型服务认证失败，请检查 API Key", null, response.bodyAsText());
        }
        if (response.statusCode() == 404 || response.statusCode() == 405) {
            throw new CatalogFailure(ApiErrorCode.MODEL_DISCOVERY_UNSUPPORTED, HttpStatus.UNPROCESSABLE_ENTITY,
                    "当前模型服务不支持获取模型目录，请手动填写模型名称", null, response.bodyAsText());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new CatalogFailure(ApiErrorCode.MODEL_DISCOVERY_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY,
                    "模型服务暂时不可用，请稍后重试或手动填写模型名称", null, response.bodyAsText());
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root == null ? null : root.get("data");
            if (data == null || !data.isArray()) {
                throw new CatalogFailure(ApiErrorCode.MODEL_DISCOVERY_RESPONSE_INVALID, HttpStatus.BAD_GATEWAY,
                        "模型服务返回的目录格式无法识别，请手动填写模型名称", null, response.bodyAsText());
            }
            Set<String> unique = new LinkedHashSet<>();
            for (JsonNode item : data) {
                JsonNode id = item.get("id");
                if (id != null && id.isTextual()) {
                    String value = id.textValue().trim();
                    if (!value.isBlank() && value.length() <= 160) {
                        unique.add(value);
                    }
                }
            }
            return unique.stream().sorted(Comparator.naturalOrder()).limit(properties.getMaxModels()).toList();
        } catch (IOException exception) {
            throw new CatalogFailure(ApiErrorCode.MODEL_DISCOVERY_RESPONSE_INVALID, HttpStatus.BAD_GATEWAY,
                    "模型服务返回的目录格式无法识别，请手动填写模型名称", exception, response.bodyAsText());
        }
    }

    private URI modelsEndpoint(String baseUrl) {
        try {
            URI base = URI.create(baseUrl == null ? "" : baseUrl.trim());
            if (base.getRawQuery() != null || base.getRawFragment() != null) {
                throw new IllegalArgumentException("baseUrl 不支持查询参数或片段");
            }
            String path = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
            return URI.create(base.getScheme() + "://" + base.getRawAuthority() + path + "/models");
        } catch (IllegalArgumentException exception) {
            throw new CatalogFailure(ApiErrorCode.MODEL_DISCOVERY_TARGET_REJECTED, HttpStatus.BAD_REQUEST,
                    "模型服务地址不允许访问", exception);
        }
    }

    private void auditFailure(PrincipalRef principal, String resourceId, ApiErrorCode errorCode) {
        auditAppender.append(principal.tenantId(), principal.principalId(), EVENT_TYPE, RESOURCE_TYPE, resourceId,
                "FAILED", "模型目录获取失败，错误码：" + errorCode.name());
    }

    private ModelCatalogDiscoveryException failure(
            PrincipalRef principal,
            String resourceId,
            String errorId,
            ApiErrorCode errorCode,
            HttpStatus status,
            String message,
            Throwable cause
    ) {
        return failure(principal, resourceId, errorId, errorCode, status, message, cause, null);
    }

    /**
     * 将供应商调用失败转换为不会向浏览器泄露细节的受控异常。
     *
     * <p>响应正文必须只沿着 {@code upstreamResponse} 传递到服务端诊断日志，并由统一日志器脱敏；
     * 不能拼接到 {@code message}，否则异常处理器会将其返回给浏览器。</p>
     */
    private ModelCatalogDiscoveryException failure(
            PrincipalRef principal,
            String resourceId,
            String errorId,
            ApiErrorCode errorCode,
            HttpStatus status,
            String message,
            Throwable cause,
            String upstreamResponse
    ) {
        return new ModelCatalogDiscoveryException(status, errorCode, message,
                new ErrorDiagnosticLogger.DiagnosticContext(errorId, "MODEL_CATALOG_DISCOVERY", errorCode.name(),
                        principal.tenantId().toString(), principal.principalId(), "-", "-", resourceId, "-", SOURCE),
                cause, upstreamResponse);
    }

    private static CatalogTransport createTransport(ModelCatalogDiscoveryProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.getTimeout())
                .build();
        return (uri, apiKey, timeout, maxResponseBytes) -> {
            if (apiKey == null || apiKey.isBlank()) {
                throw new CatalogFailure(ApiErrorCode.MODEL_DISCOVERY_AUTH_FAILED, HttpStatus.BAD_GATEWAY,
                        "模型服务认证失败，请检查 API Key", null);
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream input = response.body()) {
                    return new CatalogHttpResponse(response.statusCode(), readBounded(input, maxResponseBytes));
                }
            } catch (HttpTimeoutException exception) {
                throw new CatalogFailure(ApiErrorCode.MODEL_DISCOVERY_TIMEOUT, HttpStatus.GATEWAY_TIMEOUT,
                        "模型服务响应超时，请稍后重试或手动填写模型名称", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CatalogFailure(ApiErrorCode.MODEL_DISCOVERY_TIMEOUT, HttpStatus.GATEWAY_TIMEOUT,
                        "模型目录获取已中断，请稍后重试或手动填写模型名称", exception);
            } catch (IOException exception) {
                throw new CatalogFailure(ApiErrorCode.MODEL_DISCOVERY_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY,
                        "模型服务暂时不可用，请稍后重试或手动填写模型名称", exception);
            }
        };
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        List<byte[]> chunks = new ArrayList<>();
        for (int read; (read = input.read(buffer)) != -1; ) {
            total += read;
            if (total > maxBytes) {
                throw new CatalogFailure(ApiErrorCode.MODEL_DISCOVERY_RESPONSE_INVALID, HttpStatus.BAD_GATEWAY,
                        "模型服务返回内容过大，请手动填写模型名称", null);
            }
            byte[] chunk = new byte[read];
            System.arraycopy(buffer, 0, chunk, 0, read);
            chunks.add(chunk);
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    @FunctionalInterface
    interface CatalogTransport {
        CatalogHttpResponse get(URI uri, String apiKey, Duration timeout, int maxResponseBytes);
    }

    record CatalogHttpResponse(int statusCode, byte[] body) {
        CatalogHttpResponse {
            body = body == null ? new byte[0] : body.clone();
        }

        /**
         * 将已经受长度限制的供应商响应按 UTF-8 解码，供失败诊断日志记录。
         *
         * <p>目录协议约定 JSON 文本，因此使用固定 UTF-8；该文本只能在统一日志器脱敏后输出，
         * 不能作为对外错误消息。</p>
         *
         * @return 供应商响应正文的 UTF-8 表示
         */
        String bodyAsText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final class CatalogFailure extends RuntimeException {
        private final ApiErrorCode errorCode;
        private final HttpStatus status;

        private final String upstreamResponse;

        private CatalogFailure(ApiErrorCode errorCode, HttpStatus status, String message, Throwable cause) {
            this(errorCode, status, message, cause, null);
        }

        private CatalogFailure(ApiErrorCode errorCode, HttpStatus status, String message, Throwable cause,
                               String upstreamResponse) {
            super(message, cause);
            this.errorCode = errorCode;
            this.status = status;
            this.upstreamResponse = upstreamResponse;
        }

        private ApiErrorCode errorCode() {
            return errorCode;
        }

        private HttpStatus status() {
            return status;
        }

        private String upstreamResponse() {
            return upstreamResponse;
        }
    }
}
