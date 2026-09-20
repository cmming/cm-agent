package com.cmagent.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MigrationTest {

    private static final Set<String> REQUIRED_TABLES = Set.of(
            "tenants",
            "users",
            "roles",
            "permissions",
            "user_roles",
            "role_permissions",
            "api_keys",
            "model_configs",
            "agent_definitions",
            "tool_definitions",
            "tool_http_configs",
            "tool_mcp_publications",
            "tool_grants",
            "conversations",
            "messages",
            "runs",
            "tool_calls",
            "audit_events",
            "tool_approval_requests",
            "tool_approval_items",
            "runtime_checkpoints",
            "skill_definitions",
            "skill_versions",
            "skill_resources",
            "agent_skill_bindings",
            "run_skill_snapshots",
            "skill_load_records"
    );

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test
    /**
     * 验证 {@code migratePostgreSQL} 所描述的业务行为。
     */
    void migratePostgreSQL() {
        verifyUpgradeAndSchema(new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()),
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    /**
     * 验证 {@code migrateMySQL} 所描述的业务行为。
     */
    void migrateMySQL() {
        verifyUpgradeAndSchema(new DriverManagerDataSource(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()),
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static void verifyUpgradeAndSchema(
            DriverManagerDataSource dataSource, String jdbcUrl, String username, String password) {
        int firstStage = CmAgentFlyway.configure(dataSource).target("11").load().migrate().migrationsExecuted;
        seedLegacyRun(dataSource);
        int secondStage = CmAgentFlyway.configure(dataSource).load().migrate().migrationsExecuted;

        assertThat(firstStage).isEqualTo(11);
        assertThat(secondStage).isEqualTo(1);
        assertSchemaContract(firstStage + secondStage, jdbcUrl, username, password);
        assertThat(JdbcClient.create(dataSource).sql("""
                        SELECT skills_json FROM run_skill_snapshots
                        WHERE tenant_id = '90000000-0000-0000-0000-000000000001'
                          AND run_id = '90000000-0000-0000-0000-000000000004'
                        """).query(String.class).single()).isEqualTo("[]");
    }

    private static void seedLegacyRun(DriverManagerDataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        Timestamp now = Timestamp.from(java.time.Instant.parse("2026-09-21T00:00:00Z"));
        jdbc.sql("""
                INSERT INTO tenants (id, code, name, enabled, created_at)
                VALUES ('90000000-0000-0000-0000-000000000001', 'legacy-skill', 'legacy-skill', true, :now)
                """).param("now", now).update();
        jdbc.sql("""
                INSERT INTO model_configs (id, tenant_id, provider_type, display_name, base_url, model_name,
                    encrypted_api_key, enabled, created_at)
                VALUES ('90000000-0000-0000-0000-000000000002',
                    '90000000-0000-0000-0000-000000000001', 'OPENAI_COMPATIBLE', 'legacy',
                    'https://example.invalid', 'legacy', 'not-configured', true, :now)
                """).param("now", now).update();
        jdbc.sql("""
                INSERT INTO agent_definitions (id, tenant_id, name, description, system_prompt, model_provider_id,
                    model_name, temperature, max_iterations, enabled, tool_ids_json, created_by, updated_by,
                    created_at, updated_at)
                VALUES ('90000000-0000-0000-0000-000000000003',
                    '90000000-0000-0000-0000-000000000001', 'legacy-agent', '', 'test',
                    '90000000-0000-0000-0000-000000000002', 'legacy', 0.2, 6, true, '[]',
                    'tester', 'tester', :now, :now)
                """).param("now", now).update();
        jdbc.sql("""
                INSERT INTO runs (id, tenant_id, agent_id, principal_id, status, input_text,
                    output_text, error_message, started_at, finished_at)
                VALUES ('90000000-0000-0000-0000-000000000004',
                    '90000000-0000-0000-0000-000000000001',
                    '90000000-0000-0000-0000-000000000003', 'tester', 'RUNNING', 'legacy',
                    NULL, NULL, :now, NULL)
                """).param("now", now).update();
    }

    /**
     * 验证 {@code assertSchemaContract} 所描述的业务行为。
     *
     * @param migrationsExecuted 测试辅助方法使用的 migrationsExecuted 参数
     * @param jdbcUrl 测试辅助方法使用的 jdbcUrl 参数
     * @param username 测试辅助方法使用的 username 参数
     * @param password 测试辅助方法使用的 password 参数
     */
    private static void assertSchemaContract(int migrationsExecuted, String jdbcUrl, String username, String password) {
        assertThat(migrationsExecuted).isEqualTo(12);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            assertThat(tableNames(connection)).containsAll(REQUIRED_TABLES);
            for (String tableName : REQUIRED_TABLES) {
                assertThat(tableComment(connection, tableName))
                        .as("表 %s 应具有数据库注释", tableName)
                        .isNotBlank();
                assertThat(columnComments(connection, tableName))
                        .as("表 %s 应包含字段", tableName)
                        .isNotEmpty()
                        .allSatisfy((columnName, comment) -> assertThat(comment)
                                .as("字段 %s.%s 应具有数据库注释", tableName, columnName)
                                .isNotBlank());
            }
            assertThat(indexNames(connection, "agent_definitions")).contains("idx_agent_definitions_tenant");
            assertThat(indexNames(connection, "tool_definitions")).contains("idx_tool_definitions_tenant");
            assertThat(indexNames(connection, "tool_definitions")).contains("ux_tool_definitions_tenant_name");
            assertThat(indexNames(connection, "tool_definitions")).contains("idx_tool_definitions_tenant_deleted");
            assertThat(indexNames(connection, "tool_grants")).contains("idx_tool_grants_tenant_agent");
            assertThat(indexNames(connection, "runs")).contains("idx_runs_tenant_agent");
            assertThat(indexNames(connection, "runs")).contains("idx_runs_tenant_agent_started");
            assertThat(indexNames(connection, "tool_calls")).contains("idx_tool_calls_tenant_run");
            assertThat(indexNames(connection, "tool_calls")).contains("idx_tool_calls_tenant_run_created_at");
            assertThat(indexNames(connection, "audit_events")).contains("idx_audit_events_tenant_time");
            assertThat(indexNames(connection, "audit_events")).contains("idx_audit_events_tenant_time_id");
            assertThat(indexNames(connection, "conversations")).contains("idx_conversations_tenant_agent_updated");
            assertThat(indexNames(connection, "messages")).contains(
                    "ux_messages_tenant_conversation_sequence", "idx_messages_tenant_run");
            assertThat(indexNames(connection, "tool_approval_requests")).contains(
                    "idx_tool_approvals_pending", "idx_tool_approvals_run");
            assertThat(indexNames(connection, "tool_approval_items")).contains("ux_tool_approval_items_call");
            assertThat(indexNames(connection, "runtime_checkpoints")).contains(
                    "ux_runtime_checkpoints_slot", "idx_runtime_checkpoints_expiry");
            assertThat(indexNames(connection, "skill_definitions")).contains(
                    "ux_skill_definitions_tenant_name", "idx_skill_definitions_tenant_updated");
            assertThat(indexNames(connection, "skill_versions")).contains(
                    "ux_skill_versions_tenant_number", "ux_skill_versions_tenant_identity");
            assertThat(indexNames(connection, "skill_resources")).contains("ux_skill_resources_tenant_path");
            assertThat(indexNames(connection, "agent_skill_bindings")).contains(
                    "ux_agent_skill_bindings_tenant_agent_skill", "idx_agent_skill_bindings_tenant_skill");
            assertThat(indexNames(connection, "run_skill_snapshots")).contains("ux_run_skill_snapshots_tenant_run");
            assertThat(indexNames(connection, "skill_load_records")).contains(
                    "ux_skill_load_records_tenant_call", "idx_skill_load_records_tenant_run_time");
            assertThat(indexColumns(connection, "runs", "idx_runs_tenant_agent_started"))
                    .containsExactly("tenant_id", "agent_id", "started_at", "id");
            assertThat(indexColumns(connection, "tool_calls", "idx_tool_calls_tenant_run"))
                    .containsExactly("tenant_id", "run_id", "id");
            assertThat(indexColumns(connection, "tool_calls", "idx_tool_calls_tenant_run_created_at"))
                    .containsExactly("tenant_id", "run_id", "created_at", "id");
            assertThat(indexColumns(connection, "audit_events", "idx_audit_events_tenant_time_id"))
                    .containsExactly("tenant_id", "created_at", "id");
            assertThat(indexColumns(connection, "tool_definitions", "idx_tool_definitions_tenant_deleted"))
                    .containsExactly("tenant_id", "deleted_at");
            assertThat(isNullable(connection, "tool_definitions", "deleted_at")).isTrue();
            assertThat(isNullable(connection, "tool_definitions", "deleted_name")).isTrue();
            assertThat(isNullable(connection, "tool_grants", "role_code")).isTrue();
            assertThat(isNullable(connection, "tool_http_configs", "parameter_definitions")).isTrue();
            assertThat(isNullable(connection, "conversations", "updated_at")).isFalse();
            assertThat(isNullable(connection, "messages", "sequence_no")).isFalse();
            assertThat(isNullable(connection, "messages", "content_blocks_json")).isFalse();
            assertThat(isNullable(connection, "skill_definitions", "current_version_id")).isFalse();
            assertThat(isNullable(connection, "skill_load_records", "skill_id")).isTrue();
            assertThat(isNullable(connection, "skill_load_records", "version_id")).isTrue();
            assertThat(columnExists(connection, "tool_http_configs", "input_schema")).isFalse();
            assertThat(columnExists(connection, "tool_http_configs", "parameter_mappings")).isFalse();
            assertThat(importedKeyTargets(connection, "tool_grants")).doesNotContain("roles");
            assertThat(uniqueIndexColumns(connection, "tool_grants")).contains(Set.of("tenant_id", "tool_id", "agent_id"));
            assertThat(uniqueIndexColumns(connection, "tool_definitions")).contains(Set.of("tenant_id", "name"));
            assertThat(importedKeyTargets(connection, "tool_http_configs")).contains("tool_definitions");
            assertThat(importedKeyTargets(connection, "tool_mcp_publications")).contains("tool_definitions");
            assertThat(importedKeyTargets(connection, "messages")).contains("conversations", "runs");
            assertThat(importedKeyTargets(connection, "tool_approval_requests")).contains(
                    "tenants", "agent_definitions", "conversations", "runs");
            assertThat(importedKeyTargets(connection, "tool_approval_items")).contains(
                    "tool_approval_requests", "tenants", "tool_definitions");
            assertThat(importedKeyTargets(connection, "runtime_checkpoints")).contains("tenants");
            assertThat(importedKeyTargets(connection, "skill_versions")).contains("skill_definitions");
            assertThat(importedKeyTargets(connection, "skill_resources")).contains("skill_versions");
            assertThat(importedKeyTargets(connection, "agent_skill_bindings")).contains(
                    "agent_definitions", "skill_definitions");
            assertThat(importedKeyTargets(connection, "run_skill_snapshots")).contains("runs");
            assertThat(importedKeyTargets(connection, "skill_load_records")).contains("runs");
        } catch (SQLException e) {
            throw new AssertionError("验证迁移后的 schema 失败", e);
        }
    }

    /**
     * 验证 {@code tableNames} 所描述的业务行为。
     *
     * @param connection 测试辅助方法使用的 connection 参数
     */
    private static Set<String> tableNames(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet resultSet = metadata.getTables(null, null, "%", new String[]{"TABLE"})) {
            Set<String> names = new HashSet<>();
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                if (tableName != null) {
                    names.add(tableName.toLowerCase(Locale.ROOT));
                }
            }
            return names;
        }
    }

    /**
     * 读取指定业务表的数据库注释；直接使用 JDBC 元数据可同时覆盖 PostgreSQL 与 MySQL 驱动行为。
     *
     * @param connection 数据库连接
     * @param tableName 业务表名称
     * @return 表注释，找不到表时抛出断言错误
     */
    private static String tableComment(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet resultSet = metadata.getTables(null, null, tableName, new String[]{"TABLE"})) {
            if (!resultSet.next()) {
                throw new AssertionError("找不到表 " + tableName);
            }
            return resultSet.getString("REMARKS");
        }
    }

    /**
     * 读取指定业务表的全部字段注释，用于防止后续新增字段遗漏数据库注释。
     *
     * @param connection 数据库连接
     * @param tableName 业务表名称
     * @return 按字段名称索引的注释
     */
    private static Map<String, String> columnComments(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, String> comments = new TreeMap<>();
        try (ResultSet resultSet = metadata.getColumns(null, null, tableName, "%")) {
            while (resultSet.next()) {
                comments.put(
                        resultSet.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                        resultSet.getString("REMARKS")
                );
            }
        }
        return comments;
    }

    /**
     * 验证 {@code indexNames} 所描述的业务行为。
     *
     * @param connection 测试辅助方法使用的 connection 参数
     * @param tableName 测试辅助方法使用的 tableName 参数
     */
    private static Set<String> indexNames(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet resultSet = metadata.getIndexInfo(null, null, tableName, false, false)) {
            Set<String> names = new HashSet<>();
            while (resultSet.next()) {
                String indexName = resultSet.getString("INDEX_NAME");
                if (indexName != null) {
                    names.add(indexName.toLowerCase(Locale.ROOT));
                }
            }
            return names;
        }
    }

    /**
     * 验证 {@code indexColumns} 所描述的业务行为。
     *
     * @param connection 测试辅助方法使用的 connection 参数
     * @param tableName 测试辅助方法使用的 tableName 参数
     * @param indexName 测试辅助方法使用的 indexName 参数
     */
    private static List<String> indexColumns(Connection connection, String tableName, String indexName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Map<Short, String> columnsByPosition = new TreeMap<>();
        try (ResultSet resultSet = metadata.getIndexInfo(null, null, tableName, false, false)) {
            while (resultSet.next()) {
                String resultIndexName = resultSet.getString("INDEX_NAME");
                String columnName = resultSet.getString("COLUMN_NAME");
                if (resultIndexName == null || columnName == null || !indexName.equalsIgnoreCase(resultIndexName)) {
                    continue;
                }
                columnsByPosition.put(resultSet.getShort("ORDINAL_POSITION"), columnName.toLowerCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(columnsByPosition.values());
    }

    /**
     * 验证 {@code isNullable} 所描述的业务行为。
     *
     * @param connection 测试辅助方法使用的 connection 参数
     * @param tableName 测试辅助方法使用的 tableName 参数
     * @param columnName 测试辅助方法使用的 columnName 参数
     */
    private static boolean isNullable(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet resultSet = metadata.getColumns(null, null, tableName, columnName)) {
            if (!resultSet.next()) {
                throw new AssertionError("找不到列 " + tableName + "." + columnName);
            }
            return resultSet.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
        }
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet resultSet = metadata.getColumns(null, null, tableName, columnName)) {
            return resultSet.next();
        }
    }

    /**
     * 验证 {@code importedKeyTargets} 所描述的业务行为。
     *
     * @param connection 测试辅助方法使用的 connection 参数
     * @param tableName 测试辅助方法使用的 tableName 参数
     */
    private static Set<String> importedKeyTargets(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet resultSet = metadata.getImportedKeys(null, null, tableName)) {
            Set<String> targets = new HashSet<>();
            while (resultSet.next()) {
                String target = resultSet.getString("PKTABLE_NAME");
                if (target != null) {
                    targets.add(target.toLowerCase(Locale.ROOT));
                }
            }
            return targets;
        }
    }

    /**
     * 验证 {@code uniqueIndexColumns} 所描述的业务行为。
     *
     * @param connection 测试辅助方法使用的 connection 参数
     * @param tableName 测试辅助方法使用的 tableName 参数
     */
    private static List<Set<String>> uniqueIndexColumns(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, Set<String>> columnsByIndex = new TreeMap<>();
        try (ResultSet resultSet = metadata.getIndexInfo(null, null, tableName, true, false)) {
            while (resultSet.next()) {
                String indexName = resultSet.getString("INDEX_NAME");
                String columnName = resultSet.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) {
                    continue;
                }
                columnsByIndex
                        .computeIfAbsent(indexName.toLowerCase(Locale.ROOT), ignored -> new LinkedHashSet<>())
                        .add(columnName.toLowerCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(columnsByIndex.values());
    }
}
