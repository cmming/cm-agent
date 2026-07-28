package com.cmagent.examples.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
        httpConfig.set("inputSchema", inputSchema());
        httpConfig.set("parameterMappings", parameterMappings());
        httpConfig.set("secretHeaders", secretHeaders());
        httpConfig.put("timeoutMillis", 5000);
        return request;
    }

    private ObjectNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.putObject("properties")
                .putObject("message")
                .put("type", "string")
                .put("minLength", 1);
        schema.putArray("required").add("message");
        schema.put("additionalProperties", false);
        return schema;
    }

    private ArrayNode parameterMappings() {
        ObjectNode mapping = objectMapper.createObjectNode();
        mapping.put("sourcePointer", "/message");
        mapping.put("location", "BODY");
        mapping.put("targetName", "");
        mapping.put("targetPointer", "/message");
        mapping.put("required", true);
        return objectMapper.createArrayNode().add(mapping);
    }

    private ObjectNode secretHeaders() {
        ObjectNode secretHeaders = objectMapper.createObjectNode();
        if (!properties.getSecretHeaderName().isBlank() && !properties.getSecretRef().isBlank()) {
            secretHeaders.put(properties.getSecretHeaderName(), properties.getSecretRef());
        }
        return secretHeaders;
    }

    private ObjectNode debugRequest() {
        ObjectNode request = objectMapper.createObjectNode();
        request.putObject("input").put("message", properties.getMessage());
        return request;
    }

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

    private String bearerToken() {
        return "Bearer " + properties.getJwt();
    }

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
        public ExampleResult {
            Objects.requireNonNull(toolId, "toolId 不能为空");
            Objects.requireNonNull(debugResponse, "debugResponse 不能为空");
        }
    }
}
