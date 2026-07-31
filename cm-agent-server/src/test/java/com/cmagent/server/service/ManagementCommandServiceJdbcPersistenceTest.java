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
import com.cmagent.persistence.JdbcAgentDefinitionRepository;
import com.cmagent.persistence.JdbcAuditEventRepository;
import com.cmagent.persistence.JdbcHttpToolConfigRepository;
import com.cmagent.persistence.JdbcMcpToolPublicationRepository;
import com.cmagent.persistence.JdbcToolDefinitionRepository;
import com.cmagent.persistence.JdbcToolGrantRepository;
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
    private static final UUID MODEL_PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final PrincipalRef PRINCIPAL =
            new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("tool:grant", "tool:delete"));

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

    @Test
    void toolUpdateRevokeAndDeletePersistAtomicallyInPostgreSql() {
        assertToolMutationsPersistAtomically(postgresDataSource());
    }

    @Test
    void toolUpdateRevokeAndDeletePersistAtomicallyInMySql() {
        assertToolMutationsPersistAtomically(mysqlDataSource());
    }

    @Test
    void auditFailuresRollBackAllToolMutationsInPostgreSql() {
        assertAuditFailuresRollBackAllToolMutations(postgresDataSource());
    }

    @Test
    void auditFailuresRollBackAllToolMutationsInMySql() {
        assertAuditFailuresRollBackAllToolMutations(mysqlDataSource());
    }

    private void assertConcurrentNameConflict(DataSource dataSource) throws Exception {
        migrateAndSeedTenant(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        JdbcToolDefinitionRepository jdbcTools = new JdbcToolDefinitionRepository(jdbcClient);
        ToolDefinitionRepository tools = new BarrierToolDefinitionRepository(jdbcTools, 2);
        AuditAppender auditAppender = new AuditAppender(new JdbcAuditEventRepository(jdbcClient, transactionTemplate));
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

    private void assertToolMutationsPersistAtomically(DataSource dataSource) {
        migrateAndSeedTenant(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JdbcToolDefinitionRepository tools = new JdbcToolDefinitionRepository(jdbcClient);
        JdbcAgentDefinitionRepository agents = new JdbcAgentDefinitionRepository(jdbcClient, objectMapper);
        JdbcToolGrantRepository grants = new JdbcToolGrantRepository(jdbcClient);
        JdbcHttpToolConfigRepository httpConfigs =
                new JdbcHttpToolConfigRepository(jdbcClient, objectMapper, transactionTemplate);
        JdbcMcpToolPublicationRepository publications =
                new JdbcMcpToolPublicationRepository(jdbcClient, transactionTemplate);
        JdbcAuditEventRepository audits = new JdbcAuditEventRepository(jdbcClient, transactionTemplate);
        ManagementCommandService service = service(
                transactionTemplate, agents, tools, httpConfigs, publications, grants, new AuditAppender(audits)
        );

        ToolDefinition tool = service.createTool(
                PRINCIPAL, "orders", "订单工具", ToolType.HTTP, ToolRiskLevel.MEDIUM, httpToolSpec(), true
        );
        AgentDefinition agent = agents.save(agent(UUID.randomUUID(), List.of(tool.id())));
        grants.save(new ToolGrant(TENANT_ID, tool.id(), agent.id(), null, true));
        HttpToolCreateSpec replacement = replacementHttpToolSpec();

        ToolDefinition updated = service.updateTool(
                PRINCIPAL,
                tool.id(),
                new ManagementCommandService.ToolUpdateSpec(
                        "orders_v2",
                        "新版订单工具",
                        ToolType.HTTP,
                        ToolRiskLevel.HIGH,
                        false,
                        replacement,
                        false
                )
        );
        AgentDefinition revoked = service.revokeTool(PRINCIPAL, tool.id(), agent.id());
        service.deleteTool(PRINCIPAL, tool.id());

        assertThat(updated.name()).isEqualTo("orders_v2");
        assertThat(updated.createdBy()).isEqualTo(tool.createdBy());
        assertThat(revoked.toolIds()).doesNotContain(tool.id());
        assertThat(tools.findByTenantAndId(TENANT_ID, tool.id())).isEmpty();
        assertThat(httpConfigs.findByTenantAndToolId(TENANT_ID, tool.id())).isEmpty();
        assertThat(publications.findByTenantAndToolId(TENANT_ID, tool.id())).isEmpty();
        assertThat(grants.listByTenantAgentAndTool(TENANT_ID, agent.id(), tool.id())).isEmpty();
        assertThat(audits.listByTenant(TENANT_ID, 100))
                .extracting(AuditEvent::eventType)
                .contains("TOOL_UPDATE", "TOOL_GRANT_REVOKE", "TOOL_DELETE");
    }

    private void assertAuditFailuresRollBackAllToolMutations(DataSource dataSource) {
        migrateAndSeedTenant(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JdbcToolDefinitionRepository tools = new JdbcToolDefinitionRepository(jdbcClient);
        JdbcAgentDefinitionRepository agents = new JdbcAgentDefinitionRepository(jdbcClient, objectMapper);
        JdbcToolGrantRepository grants = new JdbcToolGrantRepository(jdbcClient);
        JdbcHttpToolConfigRepository httpConfigs =
                new JdbcHttpToolConfigRepository(jdbcClient, objectMapper, transactionTemplate);
        JdbcMcpToolPublicationRepository publications =
                new JdbcMcpToolPublicationRepository(jdbcClient, transactionTemplate);
        JdbcAuditEventRepository audits = new JdbcAuditEventRepository(jdbcClient, transactionTemplate);
        ManagementCommandService normalService = service(
                transactionTemplate, agents, tools, httpConfigs, publications, grants, new AuditAppender(audits)
        );
        ManagementCommandService failingService = service(
                transactionTemplate, agents, tools, httpConfigs, publications, grants,
                failingDatabaseAuditAppender(jdbcClient)
        );
        ToolDefinition updateTarget = normalService.createTool(
                PRINCIPAL, "update_target", "更新目标", ToolType.HTTP, ToolRiskLevel.LOW, httpToolSpec(), true
        );
        ToolDefinition revokeTarget = normalService.createTool(
                PRINCIPAL, "revoke_target", "撤销目标", ToolType.HTTP, ToolRiskLevel.LOW, httpToolSpec(), true
        );
        ToolDefinition deleteTarget = normalService.createTool(
                PRINCIPAL, "delete_target", "删除目标", ToolType.HTTP, ToolRiskLevel.LOW, httpToolSpec(), true
        );
        AgentDefinition agent = agents.save(agent(UUID.randomUUID(), List.of(revokeTarget.id())));
        grants.save(new ToolGrant(TENANT_ID, revokeTarget.id(), agent.id(), null, true));

        assertThatThrownBy(() -> failingService.updateTool(
                PRINCIPAL,
                updateTarget.id(),
                new ManagementCommandService.ToolUpdateSpec(
                        "updated_name",
                        "不应提交",
                        ToolType.HTTP,
                        ToolRiskLevel.HIGH,
                        false,
                        replacementHttpToolSpec(),
                        false
                )
        )).isInstanceOf(AuditPersistenceException.class);
        assertThatThrownBy(() -> failingService.revokeTool(PRINCIPAL, revokeTarget.id(), agent.id()))
                .isInstanceOf(AuditPersistenceException.class);
        assertThatThrownBy(() -> failingService.deleteTool(PRINCIPAL, deleteTarget.id()))
                .isInstanceOf(AuditPersistenceException.class);

        assertThat(tools.findByTenantAndId(TENANT_ID, updateTarget.id()))
                .get()
                .extracting(ToolDefinition::name)
                .isEqualTo("update_target");
        assertThat(httpConfigs.findByTenantAndToolId(TENANT_ID, updateTarget.id()))
                .get()
                .extracting(com.cmagent.core.domain.HttpToolConfig::urlTemplate)
                .isEqualTo(httpToolSpec().urlTemplate());
        assertThat(publications.findByTenantAndToolId(TENANT_ID, updateTarget.id())).isPresent();
        assertThat(agents.findByTenantAndId(TENANT_ID, agent.id())).get()
                .extracting(AgentDefinition::toolIds)
                .asList()
                .contains(revokeTarget.id());
        assertThat(grants.listByTenantAgentAndTool(TENANT_ID, agent.id(), revokeTarget.id())).hasSize(1);
        assertThat(tools.findByTenantAndId(TENANT_ID, deleteTarget.id())).isPresent();
        assertThat(httpConfigs.findByTenantAndToolId(TENANT_ID, deleteTarget.id())).isPresent();
        assertThat(publications.findByTenantAndToolId(TENANT_ID, deleteTarget.id())).isPresent();
        assertThat(audits.listByTenant(TENANT_ID, 100))
                .extracting(AuditEvent::eventType)
                .doesNotContain("TOOL_UPDATE", "TOOL_GRANT_REVOKE", "TOOL_DELETE");
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
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}",
                List.of(new HttpParameterMapping("/id", HttpParameterLocation.QUERY, "id", "", true, "")),
                java.util.Map.of("X-Api-Key", "secret/integration/api-key"),
                java.time.Duration.ofSeconds(1)
        );
    }

    private static HttpToolCreateSpec replacementHttpToolSpec() {
        return new HttpToolCreateSpec(
                HttpToolMethod.POST,
                "https://api.example.test/v2/orders",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}",
                List.of(new HttpParameterMapping("/id", HttpParameterLocation.QUERY, "orderId", "", true, "")),
                java.util.Map.of("X-Api-Key", "secret/integration/api-key-v2"),
                java.time.Duration.ofSeconds(2)
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

    private static ManagementCommandService service(
            TransactionTemplate transactionTemplate,
            AgentDefinitionRepository agents,
            ToolDefinitionRepository tools,
            JdbcHttpToolConfigRepository httpConfigs,
            JdbcMcpToolPublicationRepository publications,
            ToolGrantRepository grants,
            AuditAppender auditAppender
    ) {
        return new ManagementCommandService(
                agents,
                tools,
                httpConfigs,
                publications,
                grants,
                auditAppender,
                new HttpToolConfigValidator(new com.fasterxml.jackson.databind.ObjectMapper()),
                transactionTemplate
        );
    }

    private static AgentDefinition agent(UUID agentId, List<UUID> toolIds) {
        return new AgentDefinition(
                agentId,
                TENANT_ID,
                "订单助手",
                "",
                "处理订单",
                MODEL_PROVIDER_ID,
                "qwen-max",
                0.2d,
                6,
                true,
                toolIds,
                PRINCIPAL.principalId(),
                PRINCIPAL.principalId()
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
        JdbcClient.create(dataSource).sql("""
                        INSERT INTO model_configs (
                            id, tenant_id, provider_type, display_name, base_url,
                            model_name, encrypted_api_key, enabled, created_at
                        ) VALUES (
                            :id, :tenantId, :providerType, :displayName, :baseUrl,
                            :modelName, :encryptedApiKey, true, :createdAt
                        )
                        """)
                .param("id", MODEL_PROVIDER_ID.toString())
                .param("tenantId", TENANT_ID.toString())
                .param("providerType", "OPENAI_COMPATIBLE")
                .param("displayName", "测试模型")
                .param("baseUrl", "https://api.example.test")
                .param("modelName", "qwen-max")
                .param("encryptedApiKey", "test-only-placeholder")
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

            @Override
            public AgentDefinition removeToolFromAgent(UUID tenantId, UUID agentId, UUID toolId) {
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

            @Override
            public void delete(UUID tenantId, UUID agentId, UUID toolId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deleteByTenantAndToolId(UUID tenantId, UUID toolId) {
                throw new UnsupportedOperationException();
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
        public ToolDefinition update(ToolDefinition tool) {
            return delegate.update(tool);
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
