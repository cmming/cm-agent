package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.tool.InMemoryToolRegistry;
import com.cmagent.persistence.JdbcAuditEventRepository;
import com.cmagent.persistence.JdbcToolDefinitionRepository;
import com.cmagent.persistence.CmAgentFlyway;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.runtime.ToolRuntimeReadiness;
import com.cmagent.server.runtime.GovernedToolExecutionService;
import com.cmagent.server.runtime.http.DynamicHttpToolExecutor;
import com.cmagent.server.runtime.http.HttpToolProperties;
import com.cmagent.server.runtime.local.MysqlLocalExampleCatalog;
import com.cmagent.server.security.ToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 在 Rocky 容器环境验证 MySQL 安装事务和重启后运行时注册。 */
@Testcontainers
class MysqlLocalExampleServiceJdbcPersistenceTest {
    private static final UUID TENANT_ID = MysqlLocalExampleCatalog.EXAMPLE_TENANT_ID;
    private static final PrincipalRef PRINCIPAL = new PrincipalRef(
            TENANT_ID, "admin", "管理员", Set.of("tool:grant", "tool:debug")
    );

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void mysql首次安装持久化且重建Registry后仍通过治理调试链调用add() {
        DataSource dataSource = migratedAndSeededDataSource();
        TestFixture fixture = fixture(dataSource, false);

        fixture.service().install(PRINCIPAL, "add");

        assertThat(fixture.tools().listByTenant(TENANT_ID))
                .extracting(tool -> tool.id())
                .containsExactly(MysqlLocalExampleCatalog.ADD_TOOL_ID);
        assertThat(fixture.auditEvents().listByTenant(TENANT_ID, 10))
                .extracting(AuditEvent::eventType)
                .containsExactly("LOCAL_EXAMPLE_INSTALL");

        TestFixture restarted = fixture(dataSource, false);
        assertThat(restarted.service().list(PRINCIPAL))
                .filteredOn(LocalToolExampleSummary::installed)
                .allMatch(LocalToolExampleSummary::runtimeReady);
        ToolDebugResponse response = restarted.toolDebugService().debug(
                PRINCIPAL, MysqlLocalExampleCatalog.ADD_TOOL_ID, "{\"left\":0.1,\"right\":0.2}", ""
        );

        assertThat(response.success()).isTrue();
        assertThat(response.output()).isEqualTo("{\"sum\":0.3}");
        PrincipalRef otherTenant = new PrincipalRef(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "other", "其他管理员", Set.of("tool:debug"));
        assertThatThrownBy(() -> restarted.toolDebugService().debug(
                otherTenant, MysqlLocalExampleCatalog.ADD_TOOL_ID, "{\"left\":0.1,\"right\":0.2}", ""
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void mysql审计失败回滚工具定义() {
        DataSource dataSource = migratedAndSeededDataSource();
        TestFixture fixture = fixture(dataSource, true);

        assertThatThrownBy(() -> fixture.service().install(PRINCIPAL, "add"))
                .isInstanceOf(AuditPersistenceException.class);
        assertThat(fixture.tools().listByTenant(TENANT_ID)).isEmpty();
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void postgresql删除后重装原位恢复且失败审计回滚() {
        assertDeletedExampleCanBeRestored(postgresDataSource());
    }

    @Test
    /**
     * 验证方法名称所描述的业务行为。
     */
    void mysql删除后重装原位恢复且失败审计回滚() {
        assertDeletedExampleCanBeRestored(migratedAndSeededDataSource());
    }

    /**
     * 验证或支持 {@code assertDeletedExampleCanBeRestored} 所描述的测试场景。
     *
     * @param dataSource 测试数据源
     */
    private static void assertDeletedExampleCanBeRestored(DataSource dataSource) {
        TestFixture installed = fixture(dataSource, false);
        installed.service().install(PRINCIPAL, "echo");
        ToolDefinition original = installed.tools().findByTenantAndId(
                TENANT_ID, MysqlLocalExampleCatalog.ECHO_TOOL_ID).orElseThrow();
        installed.tools().delete(TENANT_ID, original.id());
        JdbcClient jdbcClient = JdbcClient.create(dataSource);

        assertThatThrownBy(() -> installed.tools().save(original))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(installed.tools().findByTenantAndId(TENANT_ID, original.id())).isEmpty();

        TestFixture failing = fixture(dataSource, true);
        assertThatThrownBy(() -> failing.service().install(PRINCIPAL, "echo"))
                .isInstanceOf(AuditPersistenceException.class);
        assertThat(failing.tools().findByTenantAndId(TENANT_ID, original.id())).isEmpty();
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM tool_definitions
                        WHERE tenant_id = :tenantId AND id = :id
                          AND deleted_at IS NOT NULL AND deleted_name = :name
                        """)
                .param("tenantId", TENANT_ID.toString())
                .param("id", original.id().toString())
                .param("name", original.name())
                .query(Integer.class)
                .single()).isEqualTo(1);

        TestFixture restored = fixture(dataSource, false);
        LocalToolExampleSummary summary = restored.service().install(PRINCIPAL, "echo");

        assertThat(summary.installed()).isTrue();
        assertThat(restored.tools().findByTenantAndId(TENANT_ID, original.id())).contains(original);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM tool_definitions WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", TENANT_ID.toString())
                .param("id", original.id().toString())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM tool_definitions
                        WHERE tenant_id = :tenantId AND id = :id
                          AND deleted_at IS NULL AND deleted_name IS NULL
                        """)
                .param("tenantId", TENANT_ID.toString())
                .param("id", original.id().toString())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(restored.auditEvents().listByTenant(TENANT_ID, 10))
                .extracting(AuditEvent::eventType)
                .containsExactly("LOCAL_EXAMPLE_INSTALL", "LOCAL_EXAMPLE_INSTALL");
    }

    /**
     * 验证或支持 {@code fixture} 所描述的测试场景。
     *
     * @param dataSource 测试数据源
     * @param failAudit 测试辅助方法使用的 failAudit 参数
     */
    private static TestFixture fixture(DataSource dataSource, boolean failAudit) {
        ObjectMapper objectMapper = new ObjectMapper();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        JdbcToolDefinitionRepository tools = new JdbcToolDefinitionRepository(jdbcClient);
        AuditEventRepository auditEvents = failAudit ? failingAuditRepository(jdbcClient)
                : new JdbcAuditEventRepository(jdbcClient, transactions);
        MysqlLocalExampleCatalog catalog = new MysqlLocalExampleCatalog(objectMapper);
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        catalog.list().forEach(example -> registry.register(example.definition(), example.executor()));
        AuditAppender auditAppender = new AuditAppender(auditEvents);
        HttpToolProperties httpToolProperties = new HttpToolProperties();
        MysqlLocalExampleService service = new MysqlLocalExampleService(tools, transactions, auditAppender,
                catalog, new ToolRuntimeReadiness(registry, new HttpToolProperties()), objectMapper);
        GovernedToolExecutionService governedExecution = new GovernedToolExecutionService(
                mock(HttpToolConfigRepository.class), mock(DynamicHttpToolExecutor.class), registry
        );
        ToolDebugService toolDebugService = new ToolDebugService(tools, governedExecution, auditAppender,
                new ToolOutputSanitizer(objectMapper), httpToolProperties);
        return new TestFixture(service, toolDebugService, tools, auditEvents);
    }

    /**
     * 验证或支持 {@code failingAuditRepository} 所描述的测试场景。
     *
     * @param jdbcClient 测试 JDBC 客户端
     */
    private static AuditEventRepository failingAuditRepository(JdbcClient jdbcClient) {
        return new AuditEventRepository() {
            @Override
            /**
             * 验证或支持 {@code append} 所描述的测试场景。
             *
             * @param event 测试审计事件
             */
            public void append(AuditEvent event) {
                jdbcClient.sql("INSERT INTO audit_events (id) VALUES (:id)")
                        .param("id", UUID.randomUUID().toString())
                        .update();
            }

            @Override
            /**
             * 验证或支持 {@code listByTenant} 所描述的测试场景。
             *
             * @param tenantId 测试租户标识
             * @param limit 测试辅助方法使用的 limit 参数
             */
            public List<AuditEvent> listByTenant(UUID tenantId, int limit) {
                return List.of();
            }
        };
    }

    /**
     * 验证或支持 {@code migratedAndSeededDataSource} 所描述的测试场景。
     */
    private static DataSource migratedAndSeededDataSource() {
        return migratedAndSeededDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    /**
     * 验证或支持 {@code postgresDataSource} 所描述的测试场景。
     */
    private static DataSource postgresDataSource() {
        return migratedAndSeededDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /**
     * 验证或支持 {@code migratedAndSeededDataSource} 所描述的测试场景。
     *
     * @param url 测试辅助方法使用的 url 参数
     * @param username 测试辅助方法使用的 username 参数
     * @param password 测试辅助方法使用的 password 参数
     */
    private static DataSource migratedAndSeededDataSource(String url, String username, String password) {
        DataSource dataSource = new DriverManagerDataSource(url, username, password);
        CmAgentFlyway.configure(dataSource).cleanDisabled(false).load().clean();
        CmAgentFlyway.configure(dataSource).load().migrate();
        JdbcClient.create(dataSource).sql("""
                        INSERT INTO tenants (id, code, name, enabled, created_at)
                        VALUES (:id, :code, :name, true, :createdAt)
                        """)
                .param("id", TENANT_ID.toString())
                .param("code", "tenant-a")
                .param("name", "租户A")
                .param("createdAt", Timestamp.from(Instant.parse("2026-07-30T00:00:00Z")))
                .update();
        return dataSource;
    }

    private record TestFixture(
            MysqlLocalExampleService service,
            ToolDebugService toolDebugService,
            ToolDefinitionRepository tools,
            AuditEventRepository auditEvents
    ) {
    }
}
