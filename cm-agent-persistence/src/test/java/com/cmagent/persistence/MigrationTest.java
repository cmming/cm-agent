package com.cmagent.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DataIntegrityViolationException;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            "skill_load_records",
            "skill_dependencies",
            "skill_dependency_mappings",
            "skill_releases",
            "skill_preflight_checks",
            "skill_preflight_items",
            "skill_trials", "skill_sandbox_endpoints", "skill_sandbox_defaults"
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
        CmAgentFlyway.configure(dataSource).cleanDisabled(false).load().clean();
        int firstStage = CmAgentFlyway.configure(dataSource).target("12").load().migrate().migrationsExecuted;
        seedLegacyRun(dataSource);
        int secondStage = CmAgentFlyway.configure(dataSource).target("16").load().migrate().migrationsExecuted;
        // V16端点没有认证类型字段；升级必须保留原SSH私钥语义，不能隐式切换密码认证。
        JdbcClient.create(dataSource).sql("""
                INSERT INTO skill_sandbox_endpoints
                (id,tenant_id,display_name,backend,connection_mode,host,port,username,enabled,
                 encrypted_credential,credential_version,revision,probe_revision,probe_status,deleted,created_at,updated_at)
                VALUES ('90000000-0000-0000-0000-000000000020','90000000-0000-0000-0000-000000000001',
                '旧SSH端点','docker','SSH','example.invalid',22,'fixture',true,'',0,1,0,'NOT_TESTED',false,:now,:now)
                """).param("now", Timestamp.from(java.time.Instant.now())).update();
        int passwordStage = CmAgentFlyway.configure(dataSource).target("17").load().migrate().migrationsExecuted;

        JdbcClient jdbc = JdbcClient.create(dataSource);
        String existingDescription = jdbc.sql("SELECT description FROM skill_versions WHERE id='90000000-0000-0000-0000-000000000006'")
                .query(String.class).single();
        // 用 DOCX 包的实际描述长度复现旧 schema 的完整性错误；失败语句不会改变历史值。
        assertThatThrownBy(() -> updateLegacyDescription(jdbc, "d".repeat(835)))
                .isInstanceOf(DataIntegrityViolationException.class);
        int descriptionStage = CmAgentFlyway.configure(dataSource).load().migrate().migrationsExecuted;
        assertThat(descriptionStage).isEqualTo(1);
        assertThat(jdbc.sql("SELECT description FROM skill_versions WHERE id='90000000-0000-0000-0000-000000000006'")
                .query(String.class).single()).isEqualTo(existingDescription);
        String boundaryDescription = "中".repeat(1023) + "😀";
        updateLegacyDescription(jdbc, boundaryDescription);
        assertThat(jdbc.sql("SELECT description FROM skill_versions WHERE id='90000000-0000-0000-0000-000000000006'")
                .query(String.class).single()).isEqualTo(boundaryDescription);
        updateLegacyDescription(jdbc, existingDescription);

        assertThat(firstStage).isEqualTo(12);
        assertThat(secondStage).isEqualTo(4);
        assertThat(passwordStage).isEqualTo(1);
        assertThat(JdbcClient.create(dataSource).sql("SELECT ssh_auth_type FROM skill_sandbox_endpoints WHERE id='90000000-0000-0000-0000-000000000020'")
                .query(String.class).single()).isEqualTo("KEY");
        assertSchemaContract(firstStage + secondStage + passwordStage + descriptionStage, jdbcUrl, username, password);
        assertThat(JdbcClient.create(dataSource).sql("""
                        SELECT skills_json FROM run_skill_snapshots
                        WHERE tenant_id = '90000000-0000-0000-0000-000000000001'
                          AND run_id = '90000000-0000-0000-0000-000000000004'
                        """).query(String.class).single())
                .contains("90000000-0000-0000-0000-000000000005");

        verifySkillReleaseMigration(JdbcClient.create(dataSource));
    }

    private static void updateLegacyDescription(JdbcClient jdbc, String description) {
        jdbc.sql("UPDATE skill_versions SET description=:description WHERE id='90000000-0000-0000-0000-000000000006'")
                .param("description", description).update();
    }

    @Test
    void legacySkillDependenciesMustBeExplicitAndValid() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        verifyInvalidLegacyDependencyColumns(dataSource);
    }

    private static void verifyInvalidLegacyDependencyColumns(DriverManagerDataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        CmAgentFlyway.configure(dataSource).cleanDisabled(false).load().clean();
        CmAgentFlyway.configure(dataSource).target("12").load().migrate();
        seedLegacyRun(dataSource);
        jdbc.sql("""
                UPDATE skill_versions SET metadata_json = '{"dependencies":{"tools":[{"key":"Search!","last":true}]}}'
                WHERE id = '90000000-0000-0000-0000-000000000006'
                """).update();
        assertThatThrownBy(() -> CmAgentFlyway.configure(dataSource).load().migrate())
                .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        CmAgentFlyway.configure(dataSource).cleanDisabled(false).load().clean();
    }

    private static void verifySkillReleaseMigration(JdbcClient jdbc) {
        UUID tenantId = UUID.fromString("90000000-0000-0000-0000-000000000001");
        UUID skillId = UUID.fromString("90000000-0000-0000-0000-000000000005");
        UUID publishedVersion = UUID.fromString("90000000-0000-0000-0000-000000000006");
        UUID bindingId = UUID.fromString("90000000-0000-0000-0000-000000000008");
        UUID runId = UUID.fromString("90000000-0000-0000-0000-000000000004");
        UUID approvalId = UUID.fromString("90000000-0000-0000-0000-000000000011");

        assertThat(jdbc.sql("""
                SELECT published_version_id FROM skill_definitions WHERE id = ?
                """).param(skillId.toString()).query(String.class).single())
                .isEqualTo(publishedVersion.toString());
        assertThat(jdbc.sql("""
                SELECT resolution_mode FROM agent_skill_bindings WHERE id = ?
                """).param(bindingId.toString()).query(String.class).single())
                .isEqualTo("FOLLOW_PUBLISHED");
        assertThat(jdbc.sql("""
                SELECT run_kind FROM runs WHERE id = ?
                """).param(runId.toString()).query(String.class).single())
                .isEqualTo("NORMAL");
        assertThat(jdbc.sql("""
                SELECT approval_scope FROM tool_approval_requests WHERE id = ?
                """).param(approvalId.toString()).query(String.class).single())
                .isEqualTo("CONVERSATION");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM skill_releases
                WHERE tenant_id = ? AND skill_id = ? AND action = 'BASELINE'
                """).param(tenantId.toString()).param(skillId.toString())
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM skill_releases
                WHERE tenant_id = ? AND skill_id = ? AND id = ? AND release_no = 1
                """).param(tenantId.toString()).param(skillId.toString())
                .param(skillId.toString()).query(Long.class).single()).isEqualTo(1L);
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
        insertLegacySkillData(jdbc, now);
    }

    /**
     * 验证 {@code assertSchemaContract} 所描述的业务行为。
     *
     * @param migrationsExecuted 测试辅助方法使用的 migrationsExecuted 参数
     * @param jdbcUrl 测试辅助方法使用的 jdbcUrl 参数
     * @param username 测试辅助方法使用的 username 参数
     * @param password 测试辅助方法使用的 password 参数
     */
    private static void insertLegacySkillData(JdbcClient jdbc, Timestamp now) {
        jdbc.sql("""
                INSERT INTO tool_definitions (id, tenant_id, name, description, type, input_schema, risk_level,
                    enabled, endpoint, created_by, updated_by, created_at, updated_at)
                VALUES ('90000000-0000-0000-0000-000000000010',
                    '90000000-0000-0000-0000-000000000001', 'legacy_tool', '', 'LOCAL', '{}', 'HIGH',
                    true, '', 'tester', 'tester', :now, :now)
                """).param("now", now).update();
        jdbc.sql("""
                INSERT INTO skill_definitions (id, tenant_id, name, current_version_id, enabled, access_epoch,
                    created_by, updated_by, created_at, updated_at)
                VALUES ('90000000-0000-0000-0000-000000000005',
                    '90000000-0000-0000-0000-000000000001', 'legacy-skill',
                    '90000000-0000-0000-0000-000000000006', true, 0,
                    'tester', 'tester', :now, :now)
                """).param("now", now).update();
        jdbc.sql("""
                INSERT INTO skill_versions (id, tenant_id, skill_id, version_no, description, metadata_json,
                    skill_content, sha256, created_by, created_at)
                VALUES ('90000000-0000-0000-0000-000000000006',
                    '90000000-0000-0000-0000-000000000001',
                    '90000000-0000-0000-0000-000000000005', 1, '旧技能',
                    '{"description":"旧技能","dependencies":{"tools":[{"key":"legacy-tool","required":true}]}}',
                    '旧正文',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'tester', :now)
                """).param("now", now).update();
        jdbc.sql("""
                INSERT INTO skill_versions (id, tenant_id, skill_id, version_no, description, metadata_json,
                    skill_content, sha256, created_by, created_at)
                VALUES ('90000000-0000-0000-0000-000000000007',
                    '90000000-0000-0000-0000-000000000001',
                    '90000000-0000-0000-0000-000000000005', 2, '候选技能',
                    '{"description":"候选技能"}', '候选正文',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'tester', :now)
                """).param("now", now).update();
        jdbc.sql("""
                INSERT INTO agent_skill_bindings (id, tenant_id, agent_id, skill_id, bound_by, created_at)
                VALUES ('90000000-0000-0000-0000-000000000008',
                    '90000000-0000-0000-0000-000000000001',
                    '90000000-0000-0000-0000-000000000003',
                    '90000000-0000-0000-0000-000000000005', 'tester', :now)
                """).param("now", now).update();
        jdbc.sql("""
                INSERT INTO conversations (id, tenant_id, agent_id, title, created_by, created_at, updated_at)
                VALUES ('90000000-0000-0000-0000-000000000009',
                    '90000000-0000-0000-0000-000000000001',
                    '90000000-0000-0000-0000-000000000003', '旧会话', 'tester', :now, :now)
                """).param("now", now).update();
        jdbc.sql("""
                INSERT INTO tool_approval_requests (
                    id, tenant_id, agent_id, conversation_id, run_id, requested_by,
                    requested_by_display_name, status, expires_at, version_no, checkpoint_ref,
                    created_at, updated_at)
                VALUES ('90000000-0000-0000-0000-000000000011',
                    '90000000-0000-0000-0000-000000000001',
                    '90000000-0000-0000-0000-000000000003',
                    '90000000-0000-0000-0000-000000000009',
                    '90000000-0000-0000-0000-000000000004', 'tester', '测试', 'PENDING',
                    :now, 0, 'checkpoint-legacy', :now, :now)
                """).param("now", now).update();
        jdbc.sql("""
                INSERT INTO run_skill_snapshots (tenant_id, run_id, agent_id, format_version, skills_json, created_at)
                VALUES ('90000000-0000-0000-0000-000000000001',
                    '90000000-0000-0000-0000-000000000004',
                    '90000000-0000-0000-0000-000000000003', 1,
                    '[{"skillId":"90000000-0000-0000-0000-000000000005","versionId":"90000000-0000-0000-0000-000000000006","bindingId":"90000000-0000-0000-0000-000000000008","accessEpoch":0}]',
                    :now)
                """).param("now", now).update();
    }

    private static void assertSchemaContract(int migrationsExecuted, String jdbcUrl, String username, String password) {
        assertThat(migrationsExecuted).isEqualTo(18);

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
            try (ResultSet column = connection.getMetaData().getColumns(null, null, "skill_versions", "description")) {
                assertThat(column.next()).isTrue();
                assertThat(column.getInt("COLUMN_SIZE")).isEqualTo(1024);
                assertThat(column.getString("REMARKS")).contains("1024", "Unicode");
            }
            assertThat(columnComments(connection, "skill_load_records").get("status"))
                    .contains("沙箱资源准备", "不代表交付模型或脚本成功");
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
                    "idx_tool_approvals_pending", "idx_tool_approvals_run", "idx_approval_expiry_scan");
            assertThat(indexColumns(connection, "tool_approval_requests", "idx_approval_expiry_scan"))
                    .containsExactly("status", "expires_at", "id");
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
            assertThat(indexNames(connection, "skill_versions")).contains("ux_skill_versions_tenant_number");
            assertThat(indexNames(connection, "skill_dependencies")).contains("ux_skill_dependencies_version_key");
            assertThat(indexNames(connection, "skill_releases")).contains("ux_skill_releases_tenant_release_no");
            assertThat(indexNames(connection, "skill_preflight_checks")).contains("ux_skill_preflight_checks_tenant_id");
            assertThat(indexNames(connection, "skill_preflight_items")).contains("ux_skill_preflight_items_tenant_check");
            assertThat(indexNames(connection, "skill_trials")).contains("ux_skill_trials_tenant_run");
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
            assertThat(isNullable(connection, "skill_definitions", "candidate_version_id")).isTrue();
            assertThat(isNullable(connection, "skill_definitions", "published_version_id")).isTrue();
            assertThat(isNullable(connection, "skill_definitions", "dependency_mapping_revision")).isFalse();
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
