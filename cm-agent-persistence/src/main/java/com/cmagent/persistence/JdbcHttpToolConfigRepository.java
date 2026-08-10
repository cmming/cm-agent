package com.cmagent.persistence;

import com.cmagent.core.domain.HttpParameterDefinition;
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

/** 使用 JDBC 持久化动态 HTTP 工具配置及其扁平参数定义。 */
public class JdbcHttpToolConfigRepository implements HttpToolConfigRepository {
    private static final TypeReference<List<HttpParameterDefinition>> PARAMETER_DEFINITIONS_TYPE = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> SECRET_HEADERS_TYPE = new TypeReference<>() { };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建 HTTP 工具配置仓储。
     *
     * @param jdbcClient 执行参数化 SQL 的 JDBC 客户端
     * @param objectMapper 序列化参数映射和 Secret 引用的 JSON 组件
     * @param transactionTemplate 保证工具锁定与配置写入原子性的事务模板
     */
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
    /**
     * 在事务中保存 HTTP 工具配置。
     *
     * @param config 待保存的 HTTP 工具配置
     * @return 已保存的配置
     */
    public HttpToolConfig save(HttpToolConfig config) {
        return transactionTemplate.execute(status -> saveWithinTransaction(config));
    }

    /**
     * 锁定所属工具后执行配置插入或更新，避免与工具删除并发交错。
     *
     * @param config 待保存的 HTTP 工具配置
     * @return 已保存的配置
     */
    private HttpToolConfig saveWithinTransaction(HttpToolConfig config) {
        lockToolDefinition(config.tenantId(), config.toolId());
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbcClient.sql("""
                        UPDATE tool_http_configs
                        SET method = :method,
                            url_template = :urlTemplate,
                            parameter_definitions = :parameterDefinitions,
                            secret_headers = :secretHeaders,
                            timeout_ms = :timeoutMs,
                            updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND tool_id = :toolId
                        """)
                .param("method", config.method().name())
                .param("urlTemplate", config.urlTemplate())
                .param("parameterDefinitions", writeJson(config.parameters()))
                .param("secretHeaders", writeJson(config.secretHeaders()))
                .param("timeoutMs", config.timeout().toMillis())
                .param("updatedAt", now)
                .param("tenantId", config.tenantId().toString())
                .param("toolId", config.toolId().toString())
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                            INSERT INTO tool_http_configs (
                                tenant_id, tool_id, method, url_template, parameter_definitions,
                                secret_headers, timeout_ms, created_at, updated_at
                            ) VALUES (
                                :tenantId, :toolId, :method, :urlTemplate, :parameterDefinitions,
                                :secretHeaders, :timeoutMs, :createdAt, :updatedAt
                            )
                            """)
                    .param("tenantId", config.tenantId().toString())
                    .param("toolId", config.toolId().toString())
                    .param("method", config.method().name())
                    .param("urlTemplate", config.urlTemplate())
                    .param("parameterDefinitions", writeJson(config.parameters()))
                    .param("secretHeaders", writeJson(config.secretHeaders()))
                    .param("timeoutMs", config.timeout().toMillis())
                    .param("createdAt", now)
                    .param("updatedAt", now)
                    .update();
        }
        return config;
    }

    /**
     * 按租户锁定所属工具，并拒绝不存在或已删除的工具。
     *
     * @param tenantId 租户标识
     * @param toolId 工具标识
     */
    private void lockToolDefinition(UUID tenantId, UUID toolId) {
        boolean exists = jdbcClient.sql("""
                        SELECT id
                        FROM tool_definitions
                        WHERE tenant_id = :tenantId AND id = :toolId AND deleted_at IS NULL
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
    /**
     * 查询租户内单个工具的 HTTP 配置。
     *
     * @param tenantId 租户标识
     * @param toolId 工具标识
     * @return 匹配的 HTTP 配置
     */
    public Optional<HttpToolConfig> findByTenantAndToolId(UUID tenantId, UUID toolId) {
        return jdbcClient.sql("""
                        SELECT config.tenant_id, config.tool_id, config.method, config.url_template,
                               config.parameter_definitions,
                               config.secret_headers, config.timeout_ms
                        FROM tool_http_configs config
                        INNER JOIN tool_definitions tool
                            ON tool.id = config.tool_id AND tool.tenant_id = config.tenant_id
                        WHERE config.tenant_id = :tenantId
                          AND config.tool_id = :toolId
                          AND tool.deleted_at IS NULL
                        """)
                .param("tenantId", tenantId.toString())
                .param("toolId", toolId.toString())
                .query(this::mapConfig)
                .optional();
    }

    @Override
    /**
     * 批量查询租户内多个工具的 HTTP 配置，并以工具 ID 建立索引。
     *
     * @param tenantId 租户标识
     * @param toolIds 待查询的工具标识集合
     * @return 工具 ID 到 HTTP 配置的映射
     */
    public Map<UUID, HttpToolConfig> findByTenantAndToolIds(UUID tenantId, List<UUID> toolIds) {
        if (toolIds.isEmpty()) {
            return Map.of();
        }
        List<HttpToolConfig> configurations = jdbcClient.sql("""
                        SELECT config.tenant_id, config.tool_id, config.method, config.url_template,
                               config.parameter_definitions,
                               config.secret_headers, config.timeout_ms
                        FROM tool_http_configs config
                        INNER JOIN tool_definitions tool
                            ON tool.id = config.tool_id AND tool.tenant_id = config.tenant_id
                        WHERE config.tenant_id = :tenantId
                          AND config.tool_id IN (:toolIds)
                          AND tool.deleted_at IS NULL
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
    /**
     * 删除租户内指定工具的 HTTP 配置。
     *
     * @param tenantId 租户标识
     * @param toolId 工具标识
     */
    public void delete(UUID tenantId, UUID toolId) {
        jdbcClient.sql("DELETE FROM tool_http_configs WHERE tenant_id = :tenantId AND tool_id = :toolId")
                .param("tenantId", tenantId.toString())
                .param("toolId", toolId.toString())
                .update();
    }

    /**
     * 将结果集行及其中的 JSON 字段还原为 HTTP 工具配置。
     *
     * @param resultSet 已定位到当前行的查询结果
     * @param rowNum 当前行序号，仅满足 JDBC 行映射器签名
     * @return HTTP 工具配置
     * @throws SQLException 读取列值失败时抛出
     */
    private HttpToolConfig mapConfig(ResultSet resultSet, int rowNum) throws SQLException {
        return new HttpToolConfig(
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("tool_id")),
                HttpToolMethod.valueOf(resultSet.getString("method")),
                resultSet.getString("url_template"),
                readJson(resultSet.getString("parameter_definitions"), PARAMETER_DEFINITIONS_TYPE),
                readJson(resultSet.getString("secret_headers"), SECRET_HEADERS_TYPE),
                Duration.ofMillis(resultSet.getLong("timeout_ms"))
        );
    }

    /**
     * 将配置字段序列化为数据库 JSON 文本。
     *
     * @param value 待序列化对象
     * @return JSON 文本
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 HTTP 工具配置失败", exception);
        }
    }

    /**
     * 按指定泛型类型反序列化数据库 JSON 文本。
     *
     * @param value JSON 文本
     * @param type 目标类型引用
     * @param <T> 目标对象类型
     * @return 反序列化结果
     */
    private <T> T readJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("读取 HTTP 工具配置失败", exception);
        }
    }
}
