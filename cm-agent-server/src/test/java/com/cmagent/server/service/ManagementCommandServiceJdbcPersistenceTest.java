package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.persistence.JdbcAuditEventRepository;
import com.cmagent.persistence.JdbcHttpToolConfigRepository;
import com.cmagent.persistence.JdbcMcpToolPublicationRepository;
import com.cmagent.persistence.JdbcToolDefinitionRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.http.HttpToolConfigValidator;
import com.cmagent.server.audit.AuditPersistenceException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ManagementCommandServiceJdbcPersistenceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test
    void concurrentSameTenantNameMapsThePostgreSqlUniqueConstraintToConflict() throws Exception {
        assertConcurrentNameConflict(postgresDataSource());
    }

    @Test
    void concurrentSameTenantNameMapsTheMySqlUniqueConstraintToConflict() throws Exception {
        assertConcurrentNameConflict(mysqlDataSource());
    }

    @Test
    void auditWriteFailureRollsBackHttpToolDataInPostgreSql() {
        assertAuditWriteFailureRollsBackHttpToolData(postgresDataSource());
    }

    @Test
    void auditWriteFailureRollsBackHttpToolDataInMySql() {
        assertAuditWriteFailureRollsBackHttpToolData(mysqlDataSource());
    }

    private void assertConcurrentNameConflict(DataSource dataSource) throws Exception {
        migrateAndSeedTenant(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        JdbcToolDefinitionRepository jdbcTools = new JdbcToolDefinitionRepository(jdbcClient);
        ToolDefinitionRepository tools = new BarrierToolDefinitionRepository(jdbcTools, 2);
        AuditAppender auditAppender = new AuditAppender(new JdbcAuditEventRepository(jdbcClient));
        ManagementCommandService first = service(dataSource, transactionTemplate, tools, auditAppender);
        ManagementCommandService second = service(dataSource, transactionTemplate, tools, auditAppender);
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var firstResult = executor.submit(() -> createWithStatus(first, principal));
            var secondResult = executor.submit(() -> createWithStatus(second, principal));

            assertThat(firstResult.get(30, TimeUnit.SECONDS)).isIn(200, 409);
            assertThat(secondResult.get(30, TimeUnit.SECONDS)).isIn(200, 409);
            assertThat(List.of(firstResult.get(), secondResult.get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTools.listByTenant(TENANT_ID)).hasSize(1);
    }

    private void assertAuditWriteFailureRollsBackHttpToolData(DataSource dataSource) {
        migrateAndSeedTenant(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        ManagementCommandService service = service(
                dataSource,
                transactionTemplate,
                new JdbcToolDefinitionRepository(jdbcClient),
                failingDatabaseAuditAppender(jdbcClient)
        );
        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant"));

        assertThatThrownBy(() -> service.createTool(
                principal,
                "audit-rollback",
                "审计回滚",
                ToolType.HTTP,
                ToolRiskLevel.MEDIUM,
                httpToolSpec(),
                true
        )).isInstanceOf(AuditPersistenceException.class);

        assertThat(countRows(jdbcClient, "tool_definitions")).isZero();
        assertThat(countRows(jdbcClient, "tool_http_configs")).isZero();
        assertThat(countRows(jdbcClient, "tool_mcp_publications")).isZero();
        assertThat(countRows(jdbcClient, "audit_events")).isZero();
    }

    private static AuditAppender failingDatabaseAuditAppender(JdbcClient jdbcClient) {
        AuditEventRepository repository = new AuditEventRepository() {
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
        return new AuditAppender(repository);
    }

    private static int countRows(JdbcClient jdbcClient, String tableName) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + tableName + " WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT_ID.toString())
                .query(Integer.class)
                .single();
    }

    private static HttpToolCreateSpec httpToolSpec() {
        return new HttpToolCreateSpec(
                HttpToolMethod.POST,
                "https://api.example.test/orders",
                "{\"type\":\"object\"}",
                List.of(new HttpParameterMapping("/id", HttpParameterLocation.QUERY, "id", "", true, "")),
                java.util.Map.of("X-Api-Key", "secret/integration/api-key"),
                java.time.Duration.ofSeconds(1)
        );
    }

    private static int createWithStatus(ManagementCommandService service, PrincipalRef principal) {
        try {
            service.createTool(principal, "concurrent-name", "并发测试", ToolType.LOCAL, ToolRiskLevel.LOW);
            return 200;
        } catch (ResponseStatusException exception) {
            return exception.getStatusCode().value();
        }
    }

    private static ManagementCommandService service(
            DataSource dataSource,
            TransactionTemplate transactionTemplate,
            ToolDefinitionRepository tools,
            AuditAppender auditAppender
    ) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        return new ManagementCommandService(
                emptyAgentRepository(),
                tools,
                new JdbcHttpToolConfigRepository(jdbcClient, new com.fasterxml.jackson.databind.ObjectMapper(), transactionTemplate),
                new JdbcMcpToolPublicationRepository(jdbcClient, transactionTemplate),
                emptyGrantRepository(),
                auditAppender,
                new HttpToolConfigValidator(new com.fasterxml.jackson.databind.ObjectMapper()),
                transactionTemplate
        );
    }

    private static void migrateAndSeedTenant(DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcClient.create(dataSource).sql("""
                        INSERT INTO tenants (id, code, name, enabled, created_at)
                        VALUES (:id, :code, :name, true, :createdAt)
                        """)
                .param("id", TENANT_ID.toString())
                .param("code", "tenant-a")
                .param("name", "租户A")
                .param("createdAt", Timestamp.from(Instant.parse("2026-07-22T00:00:00Z")))
                .update();
    }

    private static DataSource postgresDataSource() {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static DataSource mysqlDataSource() {
        return new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static AgentDefinitionRepository emptyAgentRepository() {
        return new AgentDefinitionRepository() {
            @Override
            public AgentDefinition save(AgentDefinition agent) {
                return agent;
            }

            @Override
            public Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
                return Optional.empty();
            }

            @Override
            public List<AgentDefinition> listByTenant(UUID tenantId) {
                return List.of();
            }

            @Override
            public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ToolGrantRepository emptyGrantRepository() {
        return new ToolGrantRepository() {
            @Override
            public ToolGrant save(ToolGrant grant) {
                return grant;
            }

            @Override
            public List<ToolGrant> listByTenant(UUID tenantId) {
                return List.of();
            }

            @Override
            public List<ToolGrant> listByTenantAndAgent(UUID tenantId, UUID agentId) {
                return List.of();
            }

            @Override
            public List<ToolGrant> listByTenantAgentAndTool(UUID tenantId, UUID agentId, UUID toolId) {
                return List.of();
            }
        };
    }

    private static final class BarrierToolDefinitionRepository implements ToolDefinitionRepository {
        private final ToolDefinitionRepository delegate;
        private final CountDownLatch listBarrier;

        private BarrierToolDefinitionRepository(ToolDefinitionRepository delegate, int callers) {
            this.delegate = delegate;
            this.listBarrier = new CountDownLatch(callers);
        }

        @Override
        public ToolDefinition save(ToolDefinition tool) {
            return delegate.save(tool);
        }

        @Override
        public Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId) {
            return delegate.findByTenantAndId(tenantId, toolId);
        }

        @Override
        public List<ToolDefinition> listByTenant(UUID tenantId) {
            List<ToolDefinition> tools = delegate.listByTenant(tenantId);
            listBarrier.countDown();
            try {
                if (!listBarrier.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发名称检查未同时到达");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并发名称检查被中断", exception);
            }
            return tools;
        }

        @Override
        public void delete(UUID tenantId, UUID toolId) {
            delegate.delete(tenantId, toolId);
        }
    }
}
