package com.cmagent.persistence;

import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

public class JdbcHttpToolConfigRepository implements HttpToolConfigRepository {
    private static final TypeReference<List<HttpParameterMapping>> PARAMETER_MAPPINGS_TYPE = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> SECRET_HEADERS_TYPE = new TypeReference<>() { };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public JdbcHttpToolConfigRepository(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate 不能为空");
    }

    @Override
    public HttpToolConfig save(HttpToolConfig config) {
        return transactionTemplate.execute(status -> saveWithinTransaction(config));
    }

    private HttpToolConfig saveWithinTransaction(HttpToolConfig config) {
        lockToolDefinition(config.tenantId(), config.toolId());
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbcClient.sql("""
                        UPDATE tool_http_configs
                        SET method = :method,
                            url_template = :urlTemplate,
                            input_schema = :inputSchema,
                            parameter_mappings = :parameterMappings,
                            secret_headers = :secretHeaders,
                            timeout_ms = :timeoutMs,
                            updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND tool_id = :toolId
                        """)
                .param("method", config.method().name())
                .param("urlTemplate", config.urlTemplate())
                .param("inputSchema", config.inputSchema())
                .param("parameterMappings", writeJson(config.parameterMappings()))
                .param("secretHeaders", writeJson(config.secretHeaders()))
                .param("timeoutMs", config.timeout().toMillis())
                .param("updatedAt", now)
                .param("tenantId", config.tenantId().toString())
                .param("toolId", config.toolId().toString())
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                            INSERT INTO tool_http_configs (
                                tenant_id, tool_id, method, url_template, input_schema, parameter_mappings,
                                secret_headers, timeout_ms, created_at, updated_at
                            ) VALUES (
                                :tenantId, :toolId, :method, :urlTemplate, :inputSchema, :parameterMappings,
                                :secretHeaders, :timeoutMs, :createdAt, :updatedAt
                            )
                            """)
                    .param("tenantId", config.tenantId().toString())
                    .param("toolId", config.toolId().toString())
                    .param("method", config.method().name())
                    .param("urlTemplate", config.urlTemplate())
                    .param("inputSchema", config.inputSchema())
                    .param("parameterMappings", writeJson(config.parameterMappings()))
                    .param("secretHeaders", writeJson(config.secretHeaders()))
                    .param("timeoutMs", config.timeout().toMillis())
                    .param("createdAt", now)
                    .param("updatedAt", now)
                    .update();
        }
        return config;
    }

    private void lockToolDefinition(UUID tenantId, UUID toolId) {
        boolean exists = jdbcClient.sql("""
                        SELECT id
                        FROM tool_definitions
                        WHERE tenant_id = :tenantId AND id = :toolId
                        FOR UPDATE
                        """)
                .param("tenantId", tenantId.toString())
                .param("toolId", toolId.toString())
                .query((resultSet, rowNum) -> resultSet.getString("id"))
                .optional()
                .isPresent();
        if (!exists) {
            throw new IllegalArgumentException("HTTP 工具不存在或不属于当前租户");
        }
    }

    @Override
    public Optional<HttpToolConfig> findByTenantAndToolId(UUID tenantId, UUID toolId) {
        return jdbcClient.sql("""
                        SELECT tenant_id, tool_id, method, url_template, input_schema, parameter_mappings,
                               secret_headers, timeout_ms
                        FROM tool_http_configs
                        WHERE tenant_id = :tenantId AND tool_id = :toolId
                        """)
                .param("tenantId", tenantId.toString())
                .param("toolId", toolId.toString())
                .query(this::mapConfig)
                .optional();
    }

    @Override
    public Map<UUID, HttpToolConfig> findByTenantAndToolIds(UUID tenantId, List<UUID> toolIds) {
        if (toolIds.isEmpty()) {
            return Map.of();
        }
        List<HttpToolConfig> configurations = jdbcClient.sql("""
                        SELECT tenant_id, tool_id, method, url_template, input_schema, parameter_mappings,
                               secret_headers, timeout_ms
                        FROM tool_http_configs
                        WHERE tenant_id = :tenantId AND tool_id IN (:toolIds)
                        """)
                .param("tenantId", tenantId.toString())
                .param("toolIds", toolIds.stream().map(UUID::toString).toList())
                .query(this::mapConfig)
                .list();
        Map<UUID, HttpToolConfig> byToolId = new LinkedHashMap<>();
        configurations.forEach(configuration -> byToolId.put(configuration.toolId(), configuration));
        return Map.copyOf(byToolId);
    }

    @Override
    public void delete(UUID tenantId, UUID toolId) {
        jdbcClient.sql("DELETE FROM tool_http_configs WHERE tenant_id = :tenantId AND tool_id = :toolId")
                .param("tenantId", tenantId.toString())
                .param("toolId", toolId.toString())
                .update();
    }

    private HttpToolConfig mapConfig(ResultSet resultSet, int rowNum) throws SQLException {
        return new HttpToolConfig(
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("tool_id")),
                HttpToolMethod.valueOf(resultSet.getString("method")),
                resultSet.getString("url_template"),
                resultSet.getString("input_schema"),
                readJson(resultSet.getString("parameter_mappings"), PARAMETER_MAPPINGS_TYPE),
                readJson(resultSet.getString("secret_headers"), SECRET_HEADERS_TYPE),
                Duration.ofMillis(resultSet.getLong("timeout_ms"))
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 HTTP 工具配置失败", exception);
        }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("读取 HTTP 工具配置失败", exception);
        }
    }
}
