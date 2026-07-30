package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.tool.InMemoryToolRegistry;
import com.cmagent.persistence.JdbcAuditEventRepository;
import com.cmagent.persistence.JdbcToolDefinitionRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import com.cmagent.server.runtime.ToolRuntimeReadiness;
import com.cmagent.server.runtime.local.MysqlLocalExampleCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
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

/** 在 Rocky 容器环境验证 MySQL 安装事务和重启后运行时注册。 */
@Testcontainers
class MysqlLocalExampleServiceJdbcPersistenceTest {
    private static final UUID TENANT_ID = MysqlLocalExampleCatalog.EXAMPLE_TENANT_ID;
    private static final PrincipalRef PRINCIPAL = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant"));

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test
    void mysql首次安装持久化且重建Registry后仍就绪() {
        DataSource dataSource = migratedAndSeededDataSource();
        TestFixture fixture = fixture(dataSource, false);

        fixture.service().install(PRINCIPAL, "echo");

        assertThat(fixture.tools().listByTenant(TENANT_ID))
                .extracting(tool -> tool.id())
                .containsExactly(MysqlLocalExampleCatalog.ECHO_TOOL_ID);
        assertThat(fixture.auditEvents().listByTenant(TENANT_ID, 10))
                .extracting(AuditEvent::eventType)
                .containsExactly("LOCAL_EXAMPLE_INSTALL");

        TestFixture restarted = fixture(dataSource, false);
        assertThat(restarted.service().list(PRINCIPAL))
                .filteredOn(LocalToolExampleSummary::installed)
                .allMatch(LocalToolExampleSummary::runtimeReady);
    }

    @Test
    void mysql审计失败回滚工具定义() {
        DataSource dataSource = migratedAndSeededDataSource();
        TestFixture fixture = fixture(dataSource, true);

        assertThatThrownBy(() -> fixture.service().install(PRINCIPAL, "add"))
                .isInstanceOf(AuditPersistenceException.class);
        assertThat(fixture.tools().listByTenant(TENANT_ID)).isEmpty();
    }

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
        MysqlLocalExampleService service = new MysqlLocalExampleService(tools, transactions, new AuditAppender(auditEvents),
                catalog, new ToolRuntimeReadiness(registry), objectMapper);
        return new TestFixture(service, tools, auditEvents);
    }

    private static AuditEventRepository failingAuditRepository(JdbcClient jdbcClient) {
        return new AuditEventRepository() {
            @Override
            public void append(AuditEvent event) {
                jdbcClient.sql("INSERT INTO audit_events (id) VALUES (:id)")
                        .param("id", UUID.randomUUID().toString())
                        .update();
            }

            @Override
            public List<AuditEvent> listByTenant(UUID tenantId, int limit) {
                return List.of();
            }
        };
    }

    private static DataSource migratedAndSeededDataSource() {
        DataSource dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
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
            ToolDefinitionRepository tools,
            AuditEventRepository auditEvents
    ) {
    }
}
