package com.cmagent.persistence;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.ApiPageRequest;
import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.domain.RunSkillSnapshot;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillLoadRecord;
import com.cmagent.core.domain.SkillLoadStatus;
import com.cmagent.core.domain.SkillResource;
import com.cmagent.core.domain.SkillSnapshotRef;
import com.cmagent.core.domain.SkillVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在 PostgreSQL 与 MySQL 上复用同一组技能 Repository 合同测试。 */
@Testcontainers
class JdbcSkillRepositoriesTest {
    private static final UUID TENANT_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID MODEL_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MODEL_B = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID AGENT_A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID AGENT_B = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID RUN_A = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID RUN_B = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final String SHA256 = "a".repeat(64);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test
    void postgres技能仓储满足合同() throws Exception {
        verifyContracts(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @Test
    void mysql技能仓储满足合同() throws Exception {
        verifyContracts(new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
    }

    private static void verifyContracts(DataSource dataSource) throws Exception {
        CmAgentFlyway.configure(dataSource).load().migrate();
        seedData(dataSource);
        JdbcClient jdbc = JdbcClient.create(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        ObjectMapper objectMapper = new ObjectMapper();
        JdbcSkillDefinitionRepository definitions = new JdbcSkillDefinitionRepository(jdbc);
        JdbcSkillVersionRepository versions = new JdbcSkillVersionRepository(jdbc, objectMapper);
        JdbcSkillResourceRepository resources = new JdbcSkillResourceRepository(jdbc);
        JdbcAgentSkillBindingRepository bindings = new JdbcAgentSkillBindingRepository(jdbc);
        JdbcRunSkillSnapshotRepository snapshots = new JdbcRunSkillSnapshotRepository(jdbc, objectMapper);
        JdbcSkillLoadRecordRepository loads = new JdbcSkillLoadRecordRepository(jdbc);

        UUID skillA = UUID.randomUUID();
        UUID skillB = UUID.randomUUID();
        UUID versionA1 = UUID.randomUUID();
        UUID versionB1 = UUID.randomUUID();
        SkillDefinition definitionA = definition(TENANT_A, skillA, versionA1, "support-guide", true, 0);
        SkillDefinition definitionB = definition(TENANT_B, skillB, versionB1, "support-guide", true, 0);
        SkillVersion firstVersion = version(TENANT_A, skillA, versionA1, 1);
        SkillResource resource = resource(TENANT_A, skillA, versionA1, "references/中文说明.md", "你好，技能");

        execute(transactions, () -> {
            definitions.insert(definitionA);
            definitions.insert(definitionB);
            versions.insert(firstVersion);
            resources.insertAll(List.of(resource));
            return null;
        });

        assertThat(definitions.findByName(TENANT_A, "support-guide")).contains(definitionA);
        assertThat(definitions.find(TENANT_B, skillA)).isEmpty();
        assertThat(versions.find(TENANT_A, skillA, versionA1)).contains(firstVersion);
        assertThat(resources.list(TENANT_A, skillA, versionA1)).containsExactly(resource);
        assertThat(definitions.list(TENANT_A, "support", true, new ApiPageRequest(0, 20)).items())
                .containsExactly(definitionA);

        UUID versionA2 = UUID.randomUUID();
        SkillVersion secondVersion = version(TENANT_A, skillA, versionA2, 2);
        SkillDefinition next = new SkillDefinition(
                skillA, TENANT_A, definitionA.name(), versionA2, true, 0,
                definitionA.createdBy(), "updater", definitionA.createdAt(), NOW.plusSeconds(2));
        execute(transactions, () -> {
            definitions.lock(TENANT_A, skillA);
            versions.insert(secondVersion);
            assertThat(definitions.updateCurrent(next, UUID.randomUUID())).isFalse();
            assertThat(definitions.updateCurrent(next, versionA1)).isTrue();
            return null;
        });
        assertThat(definitions.find(TENANT_A, skillA)).contains(next);

        AgentSkillBinding binding = new AgentSkillBinding(
                UUID.randomUUID(), TENANT_A, AGENT_A, skillA, "tester", NOW);
        execute(transactions, () -> {
            bindings.lockAgent(TENANT_A, AGENT_A);
            bindings.insert(binding);
            return null;
        });
        assertThat(bindings.find(TENANT_A, AGENT_A, skillA)).contains(binding);
        assertThat(bindings.list(TENANT_B, AGENT_A)).isEmpty();
        assertThatThrownBy(() -> execute(transactions, () -> {
            bindings.lockAgent(TENANT_A, AGENT_A);
            bindings.insert(new AgentSkillBinding(
                    UUID.randomUUID(), TENANT_A, AGENT_A, skillA, "tester", NOW.plusSeconds(1)));
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);

        UUID competingSkillOne = UUID.randomUUID();
        UUID competingSkillTwo = UUID.randomUUID();
        execute(transactions, () -> {
            definitions.insert(definition(TENANT_B, competingSkillOne, UUID.randomUUID(), "competing-one", true, 0));
            definitions.insert(definition(TENANT_B, competingSkillTwo, UUID.randomUUID(), "competing-two", true, 0));
            return null;
        });
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> bindWithinLimit(
                    transactions, bindings, start, competingSkillOne));
            Future<Boolean> second = executor.submit(() -> bindWithinLimit(
                    transactions, bindings, start, competingSkillTwo));
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(bindings.list(TENANT_B, AGENT_B)).hasSize(1);

        RunSkillSnapshot snapshot = new RunSkillSnapshot(
                TENANT_A, RUN_A, AGENT_A, 1,
                List.of(new SkillSnapshotRef(skillA, versionA2, binding.id(), 0)), NOW);
        execute(transactions, () -> {
            snapshots.insert(snapshot);
            return null;
        });
        assertThat(snapshots.find(TENANT_A, RUN_A)).contains(snapshot);
        assertThat(snapshots.find(TENANT_B, RUN_A)).isEmpty();

        SkillLoadRecord success = new SkillLoadRecord(
                UUID.randomUUID(), TENANT_A, RUN_A, "model-call-1", 1,
                skillA, versionA2, "references/中文说明.md", SkillLoadStatus.SUCCEEDED,
                resource.byteLength(), 3, null, null, NOW.plusSeconds(3));
        execute(transactions, () -> {
            loads.insert(success);
            return null;
        });
        assertThat(loads.findByCall(TENANT_A, RUN_A, "model-call-1")).contains(success);
        assertThat(loads.listForBudget(TENANT_A, RUN_A)).containsExactly(success);
        assertThat(loads.list(TENANT_A, RUN_A, new ApiPageRequest(0, 20)).items()).containsExactly(success);
        assertThatThrownBy(() -> execute(transactions, () -> {
            loads.insert(new SkillLoadRecord(
                    UUID.randomUUID(), TENANT_A, RUN_A, "model-call-1", 2,
                    skillA, versionA2, "SKILL.md", SkillLoadStatus.SUCCEEDED,
                    4, 1, null, null, NOW.plusSeconds(4)));
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);

        SkillLoadRecord denied = new SkillLoadRecord(
                UUID.randomUUID(), TENANT_B, RUN_B, "model-call-denied", 1,
                null, null, null, SkillLoadStatus.DENIED,
                0, 1, ApiErrorCode.SKILL_NOT_FOUND, "error-denied", NOW.plusSeconds(4));
        execute(transactions, () -> {
            loads.insert(denied);
            return null;
        });
        assertThat(loads.findByCall(TENANT_B, RUN_B, "model-call-denied")).contains(denied);

        UUID rolledBackSkill = UUID.randomUUID();
        assertThatThrownBy(() -> execute(transactions, () -> {
            definitions.insert(definition(TENANT_A, rolledBackSkill, UUID.randomUUID(), "rollback", false, 0));
            throw new IllegalStateException("模拟严格审计失败");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(definitions.find(TENANT_A, rolledBackSkill)).isEmpty();
    }

    private static <T> T execute(TransactionTemplate transactions, Supplier<T> operation) {
        return transactions.execute(status -> operation.get());
    }

    private static boolean bindWithinLimit(
            TransactionTemplate transactions,
            JdbcAgentSkillBindingRepository bindings,
            CountDownLatch start,
            UUID skillId
    ) throws InterruptedException {
        start.await();
        return execute(transactions, () -> {
            bindings.lockAgent(TENANT_B, AGENT_B);
            if (!bindings.list(TENANT_B, AGENT_B).isEmpty()) {
                return false;
            }
            bindings.insert(new AgentSkillBinding(
                    UUID.randomUUID(), TENANT_B, AGENT_B, skillId, "tester", NOW));
            return true;
        });
    }
    private static SkillDefinition definition(
            UUID tenantId, UUID skillId, UUID versionId, String name, boolean enabled, long accessEpoch) {
        return new SkillDefinition(
                skillId, tenantId, name, versionId, enabled, accessEpoch,
                "tester", "tester", NOW, NOW);
    }

    private static SkillVersion version(UUID tenantId, UUID skillId, UUID versionId, int number) {
        return new SkillVersion(
                versionId, tenantId, skillId, number, "测试技能", Map.of("category", "support"),
                "# 测试技能\n", SHA256, "tester", NOW.plusSeconds(number));
    }

    private static SkillResource resource(
            UUID tenantId, UUID skillId, UUID versionId, String path, String content) {
        return new SkillResource(
                tenantId, skillId, versionId, path, "text/markdown", content,
                content.getBytes(StandardCharsets.UTF_8).length, SHA256);
    }

    private static void seedData(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        Timestamp now = Timestamp.from(NOW);
        insertTenant(jdbc, TENANT_A, "tenant-skill-a", now);
        insertTenant(jdbc, TENANT_B, "tenant-skill-b", now);
        insertModel(jdbc, MODEL_A, TENANT_A, now);
        insertModel(jdbc, MODEL_B, TENANT_B, now);
        insertAgent(jdbc, AGENT_A, TENANT_A, MODEL_A, "skill-agent-a", now);
        insertAgent(jdbc, AGENT_B, TENANT_B, MODEL_B, "skill-agent-b", now);
        insertRun(jdbc, RUN_A, TENANT_A, AGENT_A, now);
        insertRun(jdbc, RUN_B, TENANT_B, AGENT_B, now);
    }

    private static void insertTenant(JdbcClient jdbc, UUID id, String code, Timestamp now) {
        jdbc.sql("INSERT INTO tenants (id, code, name, enabled, created_at) VALUES (:id, :code, :name, true, :createdAt)")
                .param("id", id.toString()).param("code", code).param("name", code).param("createdAt", now).update();
    }

    private static void insertModel(JdbcClient jdbc, UUID id, UUID tenantId, Timestamp now) {
        jdbc.sql("""
                INSERT INTO model_configs (id, tenant_id, provider_type, display_name, base_url, model_name,
                    encrypted_api_key, enabled, created_at)
                VALUES (:id, :tenantId, 'OPENAI_COMPATIBLE', 'test', 'https://example.invalid', 'test-model',
                    'not-configured', true, :createdAt)
                """).param("id", id.toString()).param("tenantId", tenantId.toString()).param("createdAt", now).update();
    }

    private static void insertAgent(
            JdbcClient jdbc, UUID id, UUID tenantId, UUID modelId, String name, Timestamp now) {
        jdbc.sql("""
                INSERT INTO agent_definitions (id, tenant_id, name, description, system_prompt, model_provider_id,
                    model_name, temperature, max_iterations, enabled, tool_ids_json, created_by, updated_by,
                    created_at, updated_at)
                VALUES (:id, :tenantId, :name, '', 'test', :modelId, 'test-model', 0.2, 6, true, '[]',
                    'tester', 'tester', :createdAt, :updatedAt)
                """).param("id", id.toString()).param("tenantId", tenantId.toString()).param("name", name)
                .param("modelId", modelId.toString()).param("createdAt", now).param("updatedAt", now).update();
    }

    private static void insertRun(JdbcClient jdbc, UUID id, UUID tenantId, UUID agentId, Timestamp now) {
        jdbc.sql("""
                INSERT INTO runs (id, tenant_id, agent_id, principal_id, status, input_text, output_text,
                    error_message, started_at, finished_at)
                VALUES (:id, :tenantId, :agentId, 'principal', 'RUNNING', 'input', NULL, NULL, :startedAt, NULL)
                """).param("id", id.toString()).param("tenantId", tenantId.toString())
                .param("agentId", agentId.toString()).param("startedAt", now).update();
    }
}
