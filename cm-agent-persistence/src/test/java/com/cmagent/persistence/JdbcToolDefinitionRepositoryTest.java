package com.cmagent.persistence;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcToolDefinitionRepositoryTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcToolDefinitionRepository repository;

    @BeforeEach
    /**
     * 准备每个测试用例共享的前置数据。
     */
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        seedTenants(dataSource);
        repository = new JdbcToolDefinitionRepository(JdbcClient.create(dataSource));
    }

    @Test
    /**
     * 验证 {@code saveFindAndListByTenant} 所描述的业务行为。
     */
    void saveFindAndListByTenant() {
        ToolDefinition toolA = tool(UUID.fromString("20000000-0000-0000-0000-000000000001"), TENANT_A, "echo");
        ToolDefinition toolB = tool(UUID.fromString("20000000-0000-0000-0000-000000000002"), TENANT_B, "calc");

        repository.save(toolA);
        repository.save(toolB);

        assertThat(repository.findByTenantAndId(TENANT_A, toolA.id())).contains(toolA);
        assertThat(repository.findByTenantAndId(TENANT_B, toolA.id())).isEmpty();
        assertThat(repository.listByTenant(TENANT_A))
                .extracting(ToolDefinition::id)
                .containsExactly(toolA.id());
    }

    @Test
    /**
     * 验证 {@code updatePersistsOnlyEditableFields} 所描述的业务行为。
     */
    void updatePersistsOnlyEditableFields() {
        UUID toolId = UUID.fromString("20000000-0000-0000-0000-000000000003");
        ToolDefinition original = tool(toolId, TENANT_A, "echo");
        ToolDefinition requested = new ToolDefinition(
                toolId, TENANT_A, "echo-v2", "更新后的描述", ToolType.HTTP,
                "{\"type\":\"object\",\"required\":[\"message\"]}", ToolRiskLevel.HIGH,
                false, "https://api.invalid/echo", "replacement-creator", "editor"
        );
        ToolDefinition expected = new ToolDefinition(
                toolId, TENANT_A, "echo-v2", "更新后的描述", ToolType.LOCAL,
                "{\"type\":\"object\",\"required\":[\"message\"]}", ToolRiskLevel.HIGH,
                false, "https://api.invalid/echo", "tester", "editor"
        );
        repository.save(original);

        assertThat(repository.update(requested)).isEqualTo(requested);
        assertThat(repository.findByTenantAndId(TENANT_A, toolId)).contains(expected);
    }

    @Test
    /**
     * 验证 {@code updateWithWrongTenantLeavesExistingToolUnchanged} 所描述的业务行为。
     */
    void updateWithWrongTenantLeavesExistingToolUnchanged() {
        UUID toolId = UUID.fromString("20000000-0000-0000-0000-000000000004");
        ToolDefinition original = tool(toolId, TENANT_A, "echo");
        ToolDefinition crossTenant = new ToolDefinition(
                toolId, TENANT_B, "other-tenant", "不应写入", ToolType.HTTP,
                "{}", ToolRiskLevel.HIGH, false, "https://api.invalid/other", "other", "editor"
        );
        repository.save(original);

        assertThatThrownBy(() -> repository.update(crossTenant))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage("工具不存在");
        assertThat(repository.findByTenantAndId(TENANT_A, toolId)).contains(original);
        assertThat(repository.findByTenantAndId(TENANT_B, toolId)).isEmpty();
    }

    /**
     * 构造测试工具定义。
     *
     * @param id 测试辅助方法使用的 id 参数
     * @param tenantId 测试租户标识
     * @param name 测试对象名称
     */
    private static ToolDefinition tool(UUID id, UUID tenantId, String name) {
        return new ToolDefinition(
                id,
                tenantId,
                name,
                "回显输入",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
                "tester",
                "tester"
        );
    }

    /**
     * 验证 {@code seedTenants} 所描述的业务行为。
     *
     * @param dataSource 测试数据源
     */
    private static void seedTenants(DataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Timestamp now = Timestamp.from(Instant.parse("2026-06-26T00:00:00Z"));
        insertTenant(jdbcClient, TENANT_A, "tenant-a", "租户A", now);
        insertTenant(jdbcClient, TENANT_B, "tenant-b", "租户B", now);
    }

    /**
     * 验证 {@code insertTenant} 所描述的业务行为。
     *
     * @param jdbcClient 测试 JDBC 客户端
     * @param tenantId 测试租户标识
     * @param code 测试辅助方法使用的 code 参数
     * @param name 测试对象名称
     * @param now 测试辅助方法使用的 now 参数
     */
    private static void insertTenant(JdbcClient jdbcClient, UUID tenantId, String code, String name, Timestamp now) {
        jdbcClient.sql("""
                        INSERT INTO tenants (id, code, name, enabled, created_at)
                        VALUES (:id, :code, :name, true, :createdAt)
                        """)
                .param("id", tenantId.toString())
                .param("code", code)
                .param("name", name)
                .param("createdAt", now)
                .update();
    }
}
