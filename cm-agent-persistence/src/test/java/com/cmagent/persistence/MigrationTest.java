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
import java.util.HashSet;
import java.util.Set;

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
                    names.add(tableName.toLowerCase());
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
                    names.add(indexName.toLowerCase());
                }
            }
            return names;
        }
    }
}
