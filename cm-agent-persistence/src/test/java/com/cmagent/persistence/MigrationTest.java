package com.cmagent.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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
            "audit_events"
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
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();

        assertSchemaContract(flyway.migrate().migrationsExecuted, postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    /**
     * 验证 {@code migrateMySQL} 所描述的业务行为。
     */
    void migrateMySQL() {
        Flyway flyway = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .load();

        assertSchemaContract(flyway.migrate().migrationsExecuted, mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
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
        assertThat(migrationsExecuted).isEqualTo(7);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            assertThat(tableNames(connection)).containsAll(REQUIRED_TABLES);
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
            assertThat(columnExists(connection, "tool_http_configs", "input_schema")).isFalse();
            assertThat(columnExists(connection, "tool_http_configs", "parameter_mappings")).isFalse();
            assertThat(importedKeyTargets(connection, "tool_grants")).doesNotContain("roles");
            assertThat(uniqueIndexColumns(connection, "tool_grants")).contains(Set.of("tenant_id", "tool_id", "agent_id"));
            assertThat(uniqueIndexColumns(connection, "tool_definitions")).contains(Set.of("tenant_id", "name"));
            assertThat(importedKeyTargets(connection, "tool_http_configs")).contains("tool_definitions");
            assertThat(importedKeyTargets(connection, "tool_mcp_publications")).contains("tool_definitions");
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
