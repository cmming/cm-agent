package com.cmagent.examples.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Objects;
import java.util.UUID;

/**
 * 通过 CM Agent 公开 REST API 创建并调试 HTTP 工具。
 */
@Component
public class CmAgentToolClient {
    private static final int MAX_ERROR_SUMMARY_LENGTH = 512;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HttpToolExampleProperties properties;

    /**
     * 校验并构造 {@code CmAgentToolClient} 实例。
     *
     * @param restClientBuilder 用于创建 CM Agent REST 客户端的构建器。
     * @param objectMapper JSON 映射器
     * @param properties 示例客户端配置
     */
    public CmAgentToolClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            HttpToolExampleProperties properties
    ) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder 不能为空")
                .baseUrl(this.properties.getBaseUrl())
                .build();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 创建一个 POST/BODY HTTP 工具，并使用调试接口执行一次调用。
     */
    public ExampleResult createAndDebug() {
        try {
            JsonNode created = restClient.post()
                    .uri("/api/tools")
                    .header(HttpHeaders.AUTHORIZATION, bearerToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createToolRequest())
                    .retrieve()
                    .body(JsonNode.class);
            UUID toolId = readToolId(created);
            JsonNode debugResponse = restClient.post()
                    .uri("/api/tools/{id}/debug", toolId)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(debugRequest())
                    .retrieve()
                    .body(JsonNode.class);
            if (debugResponse == null || !debugResponse.isObject()) {
                throw new IllegalStateException("CM Agent 调试响应为空或格式不正确");
            }
            return new ExampleResult(toolId, debugResponse);
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException(
                    "CM Agent 请求失败，HTTP " + exception.getStatusCode().value()
                            + "：" + safeErrorSummary(exception.getResponseBodyAsString())
            );
        }
    }

    /**
     * 构造动态 HTTP 工具创建请求。
     */
    private ObjectNode createToolRequest() {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("name", properties.getToolName());
        request.put("description", "通过公开 API 创建的 HTTP 工具示例");
        request.put("type", "HTTP");
        request.put("riskLevel", "LOW");
        request.put("mcpPublished", false);

        ObjectNode httpConfig = request.putObject("httpConfig");
        httpConfig.put("method", "POST");
        httpConfig.put("urlTemplate", properties.getTargetUrl());
        httpConfig.set("parameters", parameters());
        httpConfig.set("secretHeaders", secretHeaders());
        httpConfig.put("timeoutMillis", 5000);
        return request;
    }

    /**
     * 构造示例工具的扁平参数定义；输入 Schema 由服务端自动生成。
     */
    private JsonNode parameters() {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("id", "message");
        message.put("name", "message");
        message.put("dataType", "STRING");
        message.put("requestLocation", "BODY");
        message.put("description", "待发送的消息");
        message.put("required", true);
        message.put("minLength", 1);
        message.put("exampleValue", "你好，CM Agent");
        return objectMapper.createArrayNode().add(message);
    }

    /**
     * 构造请求头名称到 Secret 引用的映射。
     */
    private ObjectNode secretHeaders() {
        ObjectNode secretHeaders = objectMapper.createObjectNode();
        if (!properties.getSecretHeaderName().isBlank() && !properties.getSecretRef().isBlank()) {
            secretHeaders.put(properties.getSecretHeaderName(), properties.getSecretRef());
        }
        return secretHeaders;
    }

    /**
     * 构造创建完成后的工具调试请求。
     */
    private ObjectNode debugRequest() {
        ObjectNode request = objectMapper.createObjectNode();
        request.putObject("input").put("message", properties.getMessage());
        return request;
    }

    /**
     * 从工具创建响应中读取工具标识。
     *
     * @param created 工具创建响应。
     */
    private UUID readToolId(JsonNode created) {
        if (created == null || !created.isObject() || !created.path("id").isTextual()) {
            throw new IllegalStateException("CM Agent 创建工具响应缺少 id");
        }
        try {
            return UUID.fromString(created.path("id").asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("CM Agent 创建工具响应中的 id 无效");
        }
    }

    /**
     * 为 JWT 增加 Bearer 认证前缀。
     */
    private String bearerToken() {
        return "Bearer " + properties.getJwt();
    }

    /**
     * 从 HTTP 异常中提取不泄露响应正文的安全摘要。
     *
     * @param responseBody 外部请求返回的响应正文。
     */
    private String safeErrorSummary(String responseBody) {
        String summary = responseBody == null || responseBody.isBlank() ? "无响应内容" : responseBody;
        if (!properties.getJwt().isBlank()) {
            summary = summary.replace(properties.getJwt(), "<已脱敏>");
        }
        if (summary.length() > MAX_ERROR_SUMMARY_LENGTH) {
            return summary.substring(0, MAX_ERROR_SUMMARY_LENGTH) + "…";
        }
        return summary;
    }

    /**
     * HTTP 示例创建与调试的受控结果。
     */
    public record ExampleResult(UUID toolId, JsonNode debugResponse) {
        /**
         * 校验并构造 {@code ExampleResult} 实例。
         *
         * @param toolId 目标工具标识。
         * @param debugResponse 工具调试响应。
         */
        public ExampleResult {
            Objects.requireNonNull(toolId, "toolId 不能为空");
            Objects.requireNonNull(debugResponse, "debugResponse 不能为空");
        }
    }
}
