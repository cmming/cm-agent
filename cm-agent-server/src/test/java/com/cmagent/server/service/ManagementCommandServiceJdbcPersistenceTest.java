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
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.RunToolCall;
import com.cmagent.core.domain.RunToolCallBatch;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.persistence.JdbcAgentDefinitionRepository;
import com.cmagent.persistence.JdbcAuditEventRepository;
import com.cmagent.persistence.JdbcHttpToolConfigRepository;
import com.cmagent.persistence.JdbcMcpToolPublicationRepository;
import com.cmagent.persistence.JdbcToolDefinitionRepository;
import com.cmagent.persistence.JdbcToolGrantRepository;
import com.cmagent.persistence.JdbcToolCallRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.http.HttpToolConfigValidator;
import com.cmagent.server.audit.AuditPersistenceException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
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

    @Test
    void concurrentExistingGrantAndDeleteNeverLeaveDanglingAgentToolInPostgreSql() throws Exception {
        assertConcurrentExistingGrantAndDeleteNeverLeaveDanglingAgentTool(postgresDataSource());
    }

    @Test
    void concurrentExistingGrantAndDeleteNeverLeaveDanglingAgentToolInMySql() throws Exception {
        assertConcurrentExistingGrantAndDeleteNeverLeaveDanglingAgentTool(mysqlDataSource());
    }

    @Test
    void toolCallHistoryBlocksDeleteAndRemainsInPostgreSql() {
        assertToolCallHistoryBlocksDeleteAndRemains(postgresDataSource());
    }

    @Test
    void toolCallHistoryBlocksDeleteAndRemainsInMySql() {
        assertToolCallHistoryBlocksDeleteAndRemains(mysqlDataSource());
    }

    @Test
    void crossInstanceDifferentToolGrantAndRevokeKeepAllChangesInPostgreSql() throws Exception {
        assertCrossInstanceDifferentToolGrantAndRevokeKeepAllChanges(postgresDataSource());
    }

    @Test
    void crossInstanceDifferentToolGrantAndRevokeKeepAllChangesInMySql() throws Exception {
        assertCrossInstanceDifferentToolGrantAndRevokeKeepAllChanges(mysqlDataSource());
    }

    @Test
    void concurrentUpdateAndDeleteSerializeOnToolRowInPostgreSql() throws Exception {
        assertConcurrentUpdateAndDeleteSerializeOnToolRow(postgresDataSource());
    }

    @Test
    void concurrentUpdateAndDeleteSerializeOnToolRowInMySql() throws Exception {
        assertConcurrentUpdateAndDeleteSerializeOnToolRow(mysqlDataSource());
    }

    @Test
    void inFlightToolCallPersistsAfterRevokeAndDeleteInPostgreSql() {
        assertInFlightToolCallPersistsAfterRevokeAndDelete(postgresDataSource());
    }

    @Test
    void inFlightToolCallPersistsAfterRevokeAndDeleteInMySql() {
        assertInFlightToolCallPersistsAfterRevokeAndDelete(mysqlDataSource());
    }

    private void assertInFlightToolCallPersistsAfterRevokeAndDelete(DataSource dataSource) {
        migrateAndSeedTenant(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JdbcToolDefinitionRepository tools = new JdbcToolDefinitionRepository(jdbcClient);
        JdbcAgentDefinitionRepository agents = new JdbcAgentDefinitionRepository(jdbcClient, objectMapper, transactionTemplate);
        JdbcToolGrantRepository grants = new JdbcToolGrantRepository(jdbcClient);
        JdbcAuditEventRepository audits = new JdbcAuditEventRepository(jdbcClient, transactionTemplate);
        ManagementCommandService service = service(
                transactionTemplate,
                agents,
                tools,
                new JdbcHttpToolConfigRepository(jdbcClient, objectMapper, transactionTemplate),
                new JdbcMcpToolPublicationRepository(jdbcClient, transactionTemplate),
                grants,
                new AuditAppender(audits)
        );
        UUID toolId = UUID.fromString("20000000-0000-0000-0000-000000000091");
        UUID agentId = UUID.fromString("30000000-0000-0000-0000-000000000091");
        UUID runId = UUID.fromString("40000000-0000-0000-0000-000000000091");
        ToolDefinition tool = localTool(toolId, "in_flight_tool");
        tools.save(tool);
        agents.save(agent(agentId, List.of(toolId)));
        grants.save(new ToolGrant(TENANT_ID, toolId, agentId, null, true));
        Instant startedAt = Instant.parse("2026-07-31T00:00:00Z");
        insertRunningRun(jdbcClient, runId, agentId, startedAt);

        service.revokeTool(PRINCIPAL, toolId, agentId);
        service.deleteTool(PRINCIPAL, toolId);
        RunToolCall call = new RunToolCall(
                UUID.fromString("50000000-0000-0000-0000-000000000091"),
                TENANT_ID,
                runId,
                toolId,
                tool.name(),
                "输入摘要",
                "输出摘要",
                RunStatus.SUCCEEDED,
                true,
                12L,
                "",
                startedAt.plusSeconds(1)
        );
        JdbcToolCallRepository toolCalls = new JdbcToolCallRepository(jdbcClient, transactionTemplate);

        toolCalls.saveAll(TENANT_ID, new RunToolCallBatch(TENANT_ID, List.of(call)));

        assertThat(tools.findByTenantAndId(TENANT_ID, toolId)).isEmpty();
        assertThat(tools.listByTenant(TENANT_ID)).isEmpty();
        assertThat(tools.hasToolCallHistory(TENANT_ID, toolId)).isTrue();
        assertThat(toolCalls.listByTenantAndRun(TENANT_ID, runId)).containsExactly(call);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM tool_definitions WHERE id = :id AND deleted_at IS NOT NULL")
                .param("id", toolId.toString())
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    private void insertRunningRun(JdbcClient jdbcClient, UUID runId, UUID agentId, Instant startedAt) {
        jdbcClient.sql("""
                        INSERT INTO runs (
                            id, tenant_id, agent_id, principal_id, status, input_text,
                            output_text, error_message, started_at, finished_at
                        ) VALUES (
                            :id, :tenantId, :agentId, :principalId, 'RUNNING', :input,
                            NULL, NULL, :startedAt, NULL
                        )
                        """)
                .param("id", runId.toString())
                .param("tenantId", TENANT_ID.toString())
                .param("agentId", agentId.toString())
                .param("principalId", PRINCIPAL.principalId())
                .param("input", "运行中调用")
                .param("startedAt", Timestamp.from(startedAt))
                .update();
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
        JdbcAgentDefinitionRepository agents =
                new JdbcAgentDefinitionRepository(jdbcClient, objectMapper, transactionTemplate);
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

        ManagementCommandService.ToolUpdateResult updateResult = service.updateTool(
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
        ToolDefinition updated = updateResult.tool();
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
        JdbcAgentDefinitionRepository agents =
                new JdbcAgentDefinitionRepository(jdbcClient, objectMapper, transactionTemplate);
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

    private void assertConcurrentExistingGrantAndDeleteNeverLeaveDanglingAgentTool(DataSource dataSource)
            throws Exception {
        migrateAndSeedTenant(dataSource);
        JdbcClient setupClient = JdbcClient.create(dataSource);
        JdbcToolDefinitionRepository setupTools = new JdbcToolDefinitionRepository(setupClient);
        JdbcAgentDefinitionRepository setupAgents =
                new JdbcAgentDefinitionRepository(
                        setupClient,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                );
        JdbcToolGrantRepository setupGrants = new JdbcToolGrantRepository(setupClient);
        UUID toolId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        ToolDefinition tool = setupTools.save(localTool(toolId));
        setupAgents.save(agent(agentId, List.of()));
        setupGrants.save(new ToolGrant(TENANT_ID, toolId, agentId, null, true));

        CountDownLatch grantReadAgent = new CountDownLatch(1);
        CountDownLatch deleteSawNoReference = new CountDownLatch(1);
        CountDownLatch deleteAttemptedToolLock = new CountDownLatch(1);
        CountDownLatch grantFinished = new CountDownLatch(1);
        TransactionTemplate grantTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TransactionTemplate deleteTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcClient grantClient = JdbcClient.create(dataSource);
        JdbcClient deleteClient = JdbcClient.create(dataSource);
        Object grantService = isolatedService(
                grantTransaction,
                blockingGrantAgentRepository(
                        new JdbcAgentDefinitionRepository(
                                grantClient,
                                new com.fasterxml.jackson.databind.ObjectMapper(),
                                grantTransaction
                        ),
                        grantReadAgent,
                        deleteSawNoReference,
                        deleteAttemptedToolLock
                ),
                new JdbcToolDefinitionRepository(grantClient),
                new JdbcHttpToolConfigRepository(
                        grantClient,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        grantTransaction
                ),
                new JdbcMcpToolPublicationRepository(grantClient, grantTransaction),
                new JdbcToolGrantRepository(grantClient),
                new AuditAppender(new JdbcAuditEventRepository(grantClient, grantTransaction))
        );
        Object deleteService = isolatedService(
                deleteTransaction,
                blockingDeleteAgentRepository(
                        new JdbcAgentDefinitionRepository(
                                deleteClient,
                                new com.fasterxml.jackson.databind.ObjectMapper(),
                                deleteTransaction
                        ),
                        toolId,
                        deleteSawNoReference,
                        grantFinished
                ),
                signalingDeleteToolRepository(
                        new JdbcToolDefinitionRepository(deleteClient),
                        deleteAttemptedToolLock
                ),
                new JdbcHttpToolConfigRepository(
                        deleteClient,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        deleteTransaction
                ),
                new JdbcMcpToolPublicationRepository(deleteClient, deleteTransaction),
                new JdbcToolGrantRepository(deleteClient),
                new AuditAppender(new JdbcAuditEventRepository(deleteClient, deleteTransaction))
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var grantResult = executor.submit(() -> {
                try {
                    return grantWithStatus(grantService, tool.id(), agentId);
                } finally {
                    grantFinished.countDown();
                }
            });
            awaitLatch(grantReadAgent, "并发授权未读取到 Agent");
            var deleteResult = executor.submit(() -> deleteWithStatus(deleteService, tool.id()));

            assertThat(grantResult.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(deleteResult.get(30, TimeUnit.SECONDS)).isEqualTo(409);
        } finally {
            executor.shutdownNow();
        }

        AgentDefinition persistedAgent = setupAgents.findByTenantAndId(TENANT_ID, agentId).orElseThrow();
        assertThat(setupTools.findByTenantAndId(TENANT_ID, toolId)).isPresent();
        assertThat(persistedAgent.toolIds()).contains(toolId);
        assertThat(setupGrants.listByTenantAgentAndTool(TENANT_ID, agentId, toolId)).hasSize(1);
    }

    private void assertToolCallHistoryBlocksDeleteAndRemains(DataSource dataSource) {
        migrateAndSeedTenant(dataSource);
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        JdbcToolDefinitionRepository tools = new JdbcToolDefinitionRepository(jdbcClient);
        JdbcAgentDefinitionRepository agents =
                new JdbcAgentDefinitionRepository(jdbcClient, objectMapper, transactionTemplate);
        JdbcToolGrantRepository grants = new JdbcToolGrantRepository(jdbcClient);
        JdbcHttpToolConfigRepository httpConfigs =
                new JdbcHttpToolConfigRepository(jdbcClient, objectMapper, transactionTemplate);
        JdbcMcpToolPublicationRepository publications =
                new JdbcMcpToolPublicationRepository(jdbcClient, transactionTemplate);
        JdbcAuditEventRepository audits = new JdbcAuditEventRepository(jdbcClient, transactionTemplate);
        ManagementCommandService service = service(
                transactionTemplate, agents, tools, httpConfigs, publications, grants, new AuditAppender(audits)
        );
        UUID toolId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        tools.save(localTool(toolId, "history_tool"));
        agents.save(agent(agentId, List.of()));
        Timestamp now = Timestamp.from(Instant.parse("2026-07-31T00:00:00Z"));
        jdbcClient.sql("""
                        INSERT INTO runs (
                            id, tenant_id, agent_id, principal_id, input_text, status,
                            output_text, error_message, started_at, finished_at
                        ) VALUES (
                            :id, :tenantId, :agentId, :principalId, :input, :status,
                            :output, :errorMessage, :startedAt, :finishedAt
                        )
                        """)
                .param("id", runId.toString())
                .param("tenantId", TENANT_ID.toString())
                .param("agentId", agentId.toString())
                .param("principalId", PRINCIPAL.principalId())
                .param("input", "历史调用")
                .param("status", "SUCCEEDED")
                .param("output", "完成")
                .param("errorMessage", "")
                .param("startedAt", now)
                .param("finishedAt", now)
                .update();
        jdbcClient.sql("""
                        INSERT INTO tool_calls (
                            id, tenant_id, run_id, tool_id, tool_name, input_summary,
                            output_summary, status, authorized, duration_ms, error_message, created_at
                        ) VALUES (
                            :id, :tenantId, :runId, :toolId, :toolName, :inputSummary,
                            :outputSummary, :status, true, :durationMs, :errorMessage, :createdAt
                        )
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("tenantId", TENANT_ID.toString())
                .param("runId", runId.toString())
                .param("toolId", toolId.toString())
                .param("toolName", "history_tool")
                .param("inputSummary", "输入摘要")
                .param("outputSummary", "输出摘要")
                .param("status", "SUCCEEDED")
                .param("durationMs", 10L)
                .param("errorMessage", "")
                .param("createdAt", now)
                .update();

        assertThatThrownBy(() -> service.deleteTool(PRINCIPAL, toolId))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(409);
                    assertThat(exception.getReason()).isEqualTo("工具已有调用历史，为保留运行记录不能删除");
                });

        assertThat(tools.findByTenantAndId(TENANT_ID, toolId)).isPresent();
        assertThat(countRows(jdbcClient, "tool_calls")).isEqualTo(1);
        assertThat(audits.listByTenant(TENANT_ID, 100))
                .extracting(AuditEvent::eventType)
                .doesNotContain("TOOL_DELETE");
    }

    private void assertCrossInstanceDifferentToolGrantAndRevokeKeepAllChanges(DataSource dataSource)
            throws Exception {
        migrateAndSeedTenant(dataSource);
        JdbcClient setupClient = JdbcClient.create(dataSource);
        TransactionTemplate setupTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcToolDefinitionRepository setupTools = new JdbcToolDefinitionRepository(setupClient);
        JdbcAgentDefinitionRepository setupAgents = new JdbcAgentDefinitionRepository(
                setupClient, new com.fasterxml.jackson.databind.ObjectMapper(), setupTransaction
        );
        JdbcToolGrantRepository setupGrants = new JdbcToolGrantRepository(setupClient);
        UUID addToolId = UUID.randomUUID();
        UUID removeToolId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        setupTools.save(localTool(addToolId, "grant_tool"));
        setupTools.save(localTool(removeToolId, "revoke_tool"));
        setupAgents.save(agent(agentId, List.of(removeToolId)));
        setupGrants.save(new ToolGrant(TENANT_ID, removeToolId, agentId, null, true));

        CountDownLatch mutationsReady = new CountDownLatch(2);
        TransactionTemplate grantTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TransactionTemplate revokeTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Object grantService = isolatedServiceWithMutationBarrier(
                dataSource, grantTransaction, mutationsReady
        );
        Object revokeService = isolatedServiceWithMutationBarrier(
                dataSource, revokeTransaction, mutationsReady
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var grantResult = executor.submit(() -> grantWithStatus(grantService, addToolId, agentId));
            var revokeResult = executor.submit(() -> revokeWithStatus(revokeService, removeToolId, agentId));

            assertThat(grantResult.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(revokeResult.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            executor.shutdownNow();
        }

        AgentDefinition persisted = setupAgents.findByTenantAndId(TENANT_ID, agentId).orElseThrow();
        assertThat(persisted.toolIds()).containsExactly(addToolId);
        assertThat(setupGrants.listByTenantAgentAndTool(TENANT_ID, agentId, addToolId)).hasSize(1);
        assertThat(setupGrants.listByTenantAgentAndTool(TENANT_ID, agentId, removeToolId)).isEmpty();
    }

    private void assertConcurrentUpdateAndDeleteSerializeOnToolRow(DataSource dataSource) throws Exception {
        migrateAndSeedTenant(dataSource);
        JdbcClient setupClient = JdbcClient.create(dataSource);
        UUID toolId = UUID.randomUUID();
        new JdbcToolDefinitionRepository(setupClient).save(localTool(toolId, "serialized_tool"));
        CountDownLatch updateLocked = new CountDownLatch(1);
        CountDownLatch deleteAttemptedLock = new CountDownLatch(1);
        TransactionTemplate updateTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TransactionTemplate deleteTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcClient updateClient = JdbcClient.create(dataSource);
        JdbcClient deleteClient = JdbcClient.create(dataSource);
        Object updateService = isolatedService(
                updateTransaction,
                emptyAgentRepository(),
                pausingUpdateToolRepository(
                        new JdbcToolDefinitionRepository(updateClient),
                        updateLocked,
                        deleteAttemptedLock
                ),
                new JdbcHttpToolConfigRepository(
                        updateClient, new com.fasterxml.jackson.databind.ObjectMapper(), updateTransaction
                ),
                new JdbcMcpToolPublicationRepository(updateClient, updateTransaction),
                new JdbcToolGrantRepository(updateClient),
                new AuditAppender(new JdbcAuditEventRepository(updateClient, updateTransaction))
        );
        Object deleteService = isolatedService(
                deleteTransaction,
                emptyAgentRepository(),
                signalingDeleteToolRepository(
                        new JdbcToolDefinitionRepository(deleteClient),
                        deleteAttemptedLock
                ),
                new JdbcHttpToolConfigRepository(
                        deleteClient, new com.fasterxml.jackson.databind.ObjectMapper(), deleteTransaction
                ),
                new JdbcMcpToolPublicationRepository(deleteClient, deleteTransaction),
                new JdbcToolGrantRepository(deleteClient),
                new AuditAppender(new JdbcAuditEventRepository(deleteClient, deleteTransaction))
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var updateResult = executor.submit(
                    () -> updateLocalWithStatus(updateService, toolId, "serialized_tool")
            );
            awaitLatch(updateLocked, "并发更新未持有工具行锁");
            var deleteResult = executor.submit(() -> deleteWithStatus(deleteService, toolId));

            assertThat(updateResult.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(deleteResult.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            executor.shutdownNow();
        }

        assertThat(new JdbcToolDefinitionRepository(setupClient)
                .findByTenantAndId(TENANT_ID, toolId)).isEmpty();
        assertThat(new JdbcAuditEventRepository(setupClient, updateTransaction)
                .listByTenant(TENANT_ID, 100))
                .extracting(AuditEvent::eventType)
                .contains("TOOL_UPDATE", "TOOL_DELETE");
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

    private static int grantWithStatus(Object service, UUID toolId, UUID agentId) {
        try {
            service.getClass()
                    .getMethod("grantTool", PrincipalRef.class, UUID.class, UUID.class)
                    .invoke(service, PRINCIPAL, toolId, agentId);
            return 200;
        } catch (InvocationTargetException exception) {
            return responseStatus(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("调用隔离服务授权失败", exception);
        }
    }

    private static int revokeWithStatus(Object service, UUID toolId, UUID agentId) {
        try {
            service.getClass()
                    .getMethod("revokeTool", PrincipalRef.class, UUID.class, UUID.class)
                    .invoke(service, PRINCIPAL, toolId, agentId);
            return 200;
        } catch (InvocationTargetException exception) {
            return responseStatus(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("调用隔离服务撤销授权失败", exception);
        }
    }

    private static int updateLocalWithStatus(Object service, UUID toolId, String toolName) {
        try {
            Class<?> specClass = java.util.Arrays.stream(service.getClass().getDeclaredClasses())
                    .filter(candidate -> candidate.getSimpleName().equals("ToolUpdateSpec"))
                    .findFirst()
                    .orElseThrow();
            Object spec = specClass.getConstructor(
                            String.class,
                            String.class,
                            ToolType.class,
                            ToolRiskLevel.class,
                            boolean.class,
                            HttpToolCreateSpec.class,
                            boolean.class
                    )
                    .newInstance(
                            toolName,
                            "并发更新后的描述",
                            ToolType.LOCAL,
                            ToolRiskLevel.MEDIUM,
                            true,
                            null,
                            false
                    );
            service.getClass()
                    .getMethod("updateTool", PrincipalRef.class, UUID.class, specClass)
                    .invoke(service, PRINCIPAL, toolId, spec);
            return 200;
        } catch (InvocationTargetException exception) {
            return responseStatus(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("调用隔离服务更新失败", exception);
        }
    }

    private static int deleteWithStatus(Object service, UUID toolId) {
        try {
            service.getClass()
                    .getMethod("deleteTool", PrincipalRef.class, UUID.class)
                    .invoke(service, PRINCIPAL, toolId);
            return 200;
        } catch (InvocationTargetException exception) {
            return responseStatus(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("调用隔离服务删除失败", exception);
        }
    }

    private static int responseStatus(Throwable failure) {
        if (failure instanceof ResponseStatusException exception) {
            return exception.getStatusCode().value();
        }
        throw new IllegalStateException("隔离服务执行失败", failure);
    }

    private static Object isolatedService(
            TransactionTemplate transactionTemplate,
            AgentDefinitionRepository agents,
            ToolDefinitionRepository tools,
            HttpToolConfigRepository httpConfigs,
            McpToolPublicationRepository publications,
            ToolGrantRepository grants,
            AuditAppender auditAppender
    ) {
        try {
            ClassLoader loader = new IsolatedManagementCommandServiceClassLoader(
                    ManagementCommandService.class.getClassLoader()
            );
            Class<?> serviceClass = loader.loadClass(ManagementCommandService.class.getName());
            return serviceClass.getConstructor(
                            AgentDefinitionRepository.class,
                            ToolDefinitionRepository.class,
                            HttpToolConfigRepository.class,
                            McpToolPublicationRepository.class,
                            ToolGrantRepository.class,
                            AuditAppender.class,
                            HttpToolConfigValidator.class,
                            TransactionTemplate.class
                    )
                    .newInstance(
                            agents,
                            tools,
                            httpConfigs,
                            publications,
                            grants,
                            auditAppender,
                            new HttpToolConfigValidator(new com.fasterxml.jackson.databind.ObjectMapper()),
                            transactionTemplate
                    );
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("创建隔离的命令服务失败", exception);
        }
    }

    private static AgentDefinitionRepository blockingGrantAgentRepository(
            AgentDefinitionRepository delegate,
            CountDownLatch grantReadAgent,
            CountDownLatch deleteSawNoReference,
            CountDownLatch deleteAttemptedToolLock
    ) {
        return new DelegatingAgentDefinitionRepository(delegate) {
            @Override
            public Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
                Optional<AgentDefinition> result = super.findByTenantAndId(tenantId, agentId);
                grantReadAgent.countDown();
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    awaitLatch(deleteAttemptedToolLock, "并发删除未发起工具行锁请求");
                } else {
                    awaitLatch(deleteSawNoReference, "并发删除未完成无引用快照");
                }
                return result;
            }
        };
    }

    private static ToolDefinitionRepository signalingDeleteToolRepository(
            ToolDefinitionRepository delegate,
            CountDownLatch deleteAttemptedToolLock
    ) {
        return new ToolDefinitionRepository() {
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
            public Optional<ToolDefinition> findByTenantAndIdForUpdate(UUID tenantId, UUID toolId) {
                deleteAttemptedToolLock.countDown();
                return delegate.findByTenantAndIdForUpdate(tenantId, toolId);
            }

            @Override
            public List<ToolDefinition> listByTenant(UUID tenantId) {
                return delegate.listByTenant(tenantId);
            }

            @Override
            public boolean hasToolCallHistory(UUID tenantId, UUID toolId) {
                return delegate.hasToolCallHistory(tenantId, toolId);
            }

            @Override
            public void delete(UUID tenantId, UUID toolId) {
                delegate.delete(tenantId, toolId);
            }
        };
    }

    private static ToolDefinitionRepository pausingUpdateToolRepository(
            ToolDefinitionRepository delegate,
            CountDownLatch updateLocked,
            CountDownLatch deleteAttemptedLock
    ) {
        return new ToolDefinitionRepository() {
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
            public Optional<ToolDefinition> findByTenantAndIdForUpdate(UUID tenantId, UUID toolId) {
                Optional<ToolDefinition> result = delegate.findByTenantAndIdForUpdate(tenantId, toolId);
                updateLocked.countDown();
                awaitLatch(deleteAttemptedLock, "并发删除未发起工具行锁请求");
                return result;
            }

            @Override
            public List<ToolDefinition> listByTenant(UUID tenantId) {
                return delegate.listByTenant(tenantId);
            }

            @Override
            public boolean hasToolCallHistory(UUID tenantId, UUID toolId) {
                return delegate.hasToolCallHistory(tenantId, toolId);
            }

            @Override
            public void delete(UUID tenantId, UUID toolId) {
                delegate.delete(tenantId, toolId);
            }
        };
    }

    private static AgentDefinitionRepository blockingDeleteAgentRepository(
            AgentDefinitionRepository delegate,
            UUID toolId,
            CountDownLatch deleteSawNoReference,
            CountDownLatch grantFinished
    ) {
        return new DelegatingAgentDefinitionRepository(delegate) {
            @Override
            public List<AgentDefinition> listByTenant(UUID tenantId) {
                List<AgentDefinition> snapshot = super.listByTenant(tenantId);
                boolean referenced = snapshot.stream().anyMatch(agent -> agent.toolIds().contains(toolId));
                if (!referenced) {
                    deleteSawNoReference.countDown();
                    awaitLatch(grantFinished, "并发授权未完成");
                }
                return snapshot;
            }
        };
    }

    private static void awaitLatch(CountDownLatch latch, String message) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, exception);
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

    private static Object isolatedServiceWithMutationBarrier(
            DataSource dataSource,
            TransactionTemplate transactionTemplate,
            CountDownLatch mutationsReady
    ) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        AgentDefinitionRepository agents = new DelegatingAgentDefinitionRepository(
                new JdbcAgentDefinitionRepository(
                        jdbcClient,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        transactionTemplate
                )
        ) {
            @Override
            public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
                awaitConcurrentMutation();
                return super.addToolToAgent(tenantId, agentId, toolId);
            }

            @Override
            public AgentDefinition removeToolFromAgent(UUID tenantId, UUID agentId, UUID toolId) {
                awaitConcurrentMutation();
                return super.removeToolFromAgent(tenantId, agentId, toolId);
            }

            private void awaitConcurrentMutation() {
                mutationsReady.countDown();
                awaitLatch(mutationsReady, "同一 Agent 的并发授权与撤销未同时到达");
            }
        };
        return isolatedService(
                transactionTemplate,
                agents,
                new JdbcToolDefinitionRepository(jdbcClient),
                new JdbcHttpToolConfigRepository(
                        jdbcClient,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        transactionTemplate
                ),
                new JdbcMcpToolPublicationRepository(jdbcClient, transactionTemplate),
                new JdbcToolGrantRepository(jdbcClient),
                new AuditAppender(new JdbcAuditEventRepository(jdbcClient, transactionTemplate))
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

    private static ToolDefinition localTool(UUID toolId) {
        return localTool(toolId, "并发本地工具");
    }

    private static ToolDefinition localTool(UUID toolId, String name) {
        return new ToolDefinition(
                toolId,
                TENANT_ID,
                name,
                "用于验证授权与删除并发一致性",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
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
        public boolean hasToolCallHistory(UUID tenantId, UUID toolId) {
            return delegate.hasToolCallHistory(tenantId, toolId);
        }

        @Override
        public void delete(UUID tenantId, UUID toolId) {
            delegate.delete(tenantId, toolId);
        }
    }

    private static class DelegatingAgentDefinitionRepository implements AgentDefinitionRepository {
        private final AgentDefinitionRepository delegate;

        private DelegatingAgentDefinitionRepository(AgentDefinitionRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public AgentDefinition save(AgentDefinition agent) {
            return delegate.save(agent);
        }

        @Override
        public Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
            return delegate.findByTenantAndId(tenantId, agentId);
        }

        @Override
        public List<AgentDefinition> listByTenant(UUID tenantId) {
            return delegate.listByTenant(tenantId);
        }

        @Override
        public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
            return delegate.addToolToAgent(tenantId, agentId, toolId);
        }

        @Override
        public AgentDefinition removeToolFromAgent(UUID tenantId, UUID agentId, UUID toolId) {
            return delegate.removeToolFromAgent(tenantId, agentId, toolId);
        }
    }

    private static final class IsolatedManagementCommandServiceClassLoader extends ClassLoader {
        private static final String SERVICE_CLASS_NAME = ManagementCommandService.class.getName();

        private IsolatedManagementCommandServiceClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.equals(SERVICE_CLASS_NAME) || name.startsWith(SERVICE_CLASS_NAME + "$")) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findIsolatedClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        }

        private Class<?> findIsolatedClass(String name) throws ClassNotFoundException {
            String resourceName = name.replace('.', '/') + ".class";
            try (InputStream input = getParent().getResourceAsStream(resourceName)) {
                if (input == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytecode = input.readAllBytes();
                return defineClass(name, bytecode, 0, bytecode.length);
            } catch (IOException exception) {
                throw new ClassNotFoundException(name, exception);
            }
        }
    }
}
