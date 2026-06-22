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
    void migratePostgreSQL() {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();

        assertSchemaContract(flyway.migrate().migrationsExecuted, postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void migrateMySQL() {
        Flyway flyway = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .load();

        assertSchemaContract(flyway.migrate().migrationsExecuted, mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static void assertSchemaContract(int migrationsExecuted, String jdbcUrl, String username, String password) {
        assertThat(migrationsExecuted).isEqualTo(1);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            assertThat(tableNames(connection)).containsAll(REQUIRED_TABLES);
            assertThat(indexNames(connection, "agent_definitions")).contains("idx_agent_definitions_tenant");
            assertThat(indexNames(connection, "tool_definitions")).contains("idx_tool_definitions_tenant");
            assertThat(indexNames(connection, "tool_grants")).contains("idx_tool_grants_tenant_agent");
            assertThat(indexNames(connection, "runs")).contains("idx_runs_tenant_agent");
            assertThat(indexNames(connection, "audit_events")).contains("idx_audit_events_tenant_time");
            assertThat(isNullable(connection, "tool_grants", "role_code")).isTrue();
            assertThat(importedKeyTargets(connection, "tool_grants")).doesNotContain("roles");
            assertThat(uniqueIndexColumns(connection, "tool_grants")).contains(Set.of("tenant_id", "tool_id", "agent_id"));
        } catch (SQLException e) {
            throw new AssertionError("验证迁移后的 schema 失败", e);
        }
    }

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

    private static boolean isNullable(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet resultSet = metadata.getColumns(null, null, tableName, columnName)) {
            if (!resultSet.next()) {
                throw new AssertionError("找不到列 " + tableName + "." + columnName);
            }
            return resultSet.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
        }
    }

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
