package com.cmagent.persistence;

import com.cmagent.core.domain.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

/** 双数据库验证审批历史在仓储重建后仍可分页回放；只操作 Testcontainers 的一次性测试库。 */
@Testcontainers
class JdbcToolApprovalHistoryTest {
    // 两个容器由 Testcontainers 扩展统一创建与关闭，只在当前测试类中复用，不连接应用配置中的数据库。
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    static Stream<JdbcDatabaseContainer<?>> databases() { return Stream.of(postgres, mysql); }

    @ParameterizedTest
    @MethodSource("databases")
    void 历史重建分页保留明细并隔离租户Agent与会话(JdbcDatabaseContainer<?> database) {
        var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        // 仅清理本测试声明的临时容器库，绝不用于已有部署数据库。
        CmAgentFlyway.configure(source).cleanDisabled(false).load().clean();
        CmAgentFlyway.configure(source).load().migrate();
        var jdbc = JdbcClient.create(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        UUID tenant = UUID.randomUUID(), agent = UUID.randomUUID(), model = UUID.randomUUID();
        UUID conversation = UUID.randomUUID(), tool = UUID.randomUUID(), run = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-03T00:00:00Z");
        Timestamp timestamp = Timestamp.from(now);
        jdbc.sql("INSERT INTO tenants (id, code, name, enabled, created_at) VALUES (:id, 'history-test', '历史测试', true, :at)")
                .param("id", tenant.toString()).param("at", timestamp).update();
        jdbc.sql("""
                INSERT INTO model_configs (id, tenant_id, provider_type, display_name, base_url, model_name,
                                           encrypted_api_key, enabled, created_at)
                VALUES (:id, :tenant, 'OPENAI_COMPATIBLE', '历史测试', 'https://example.invalid', 'test-model',
                        'not-configured', true, :at)
                """).param("id", model.toString()).param("tenant", tenant.toString()).param("at", timestamp).update();
        jdbc.sql("""
                INSERT INTO agent_definitions (id, tenant_id, name, description, system_prompt, model_provider_id,
                        model_name, temperature, max_iterations, enabled, tool_ids_json, created_by, updated_by, created_at, updated_at)
                VALUES (:id, :tenant, '历史测试', '', '测试', :model, 'test-model', 0.2, 6, true, '[]', 'tester', 'tester', :at, :at)
                """).param("id", agent.toString()).param("tenant", tenant.toString()).param("model", model.toString())
                .param("at", timestamp).update();
        new JdbcConversationRepository(jdbc).save(tenant, new Conversation(conversation, tenant, agent, "历史会话", "tester", now, now));
        new JdbcRunRepository(jdbc).save(tenant, RunRecord.create(run, tenant, agent, "tester", "测试", now));
        jdbc.sql("""
                INSERT INTO tool_definitions (id, tenant_id, name, description, type, input_schema, risk_level, enabled,
                        endpoint, created_by, updated_by, created_at, updated_at)
                VALUES (:id, :tenant, 'history_tool', '历史测试工具', 'LOCAL', '{}', 'HIGH', true, '', 'tester', 'tester', :at, :at)
                """).param("id", tool.toString()).param("tenant", tenant.toString()).param("at", timestamp).update();
        var repository = new JdbcToolApprovalRepository(jdbc, tx);
        var lower = approval("10000000-0000-0000-0000-000000000001", tenant, agent, conversation, run, tool, now, ToolApprovalStatus.APPROVED);
        var higher = approval("f0000000-0000-0000-0000-000000000001", tenant, agent, conversation, run, tool, now, ToolApprovalStatus.PARTIALLY_APPROVED);
        var oldest = approval("30000000-0000-0000-0000-000000000001", tenant, agent, conversation, run, tool, now.minusSeconds(1), ToolApprovalStatus.DENIED);
        var pending = approval("90000000-0000-0000-0000-000000000001", tenant, agent, conversation, run, tool, now, ToolApprovalStatus.PENDING);
        for (var item : List.of(lower, higher, oldest, pending)) repository.save(item);
        // 新仓储实例验证状态来自数据库，不依赖内存中的最后一次决定。
        var reloaded = new JdbcToolApprovalRepository(jdbc, tx);
        var first = reloaded.listHistory(tenant, agent, conversation, new ToolApprovalHistoryPageRequest(1, null, null));
        assertThat(first).containsExactly(higher);
        var next = new ToolApprovalHistoryPageRequest(20, higher.decidedAt(), higher.id());
        assertThat(reloaded.listHistory(tenant, agent, conversation, next)).containsExactly(lower, oldest);
        assertThat(first.getFirst().items()).extracting(ToolApprovalItem::decision)
                .containsExactlyInAnyOrder(ToolApprovalDecision.APPROVE, ToolApprovalDecision.DENY);
        assertThat(reloaded.listHistory(UUID.randomUUID(), agent, conversation, next)).isEmpty();
        assertThat(reloaded.listHistory(tenant, UUID.randomUUID(), conversation, next)).isEmpty();
        assertThat(reloaded.listHistory(tenant, agent, UUID.randomUUID(), next)).isEmpty();
        assertThat(reloaded.find(tenant, agent, conversation, pending.id()).orElseThrow().status()).isEqualTo(ToolApprovalStatus.PENDING);
        var inserted = approval("40000000-0000-0000-0000-000000000001", tenant, agent, conversation, run, tool, now.plusSeconds(1), ToolApprovalStatus.EXPIRED);
        repository.save(inserted);
        assertThat(reloaded.listHistory(tenant, agent, conversation, next)).containsExactly(lower, oldest);
        assertThat(reloaded.listHistory(tenant, agent, conversation, new ToolApprovalHistoryPageRequest(20, null, null)))
                .containsExactly(inserted, higher, lower, oldest);
    }

    private static ToolApprovalRequest approval(String id, UUID tenant, UUID agent, UUID conversation,
            UUID run, UUID tool, Instant decidedAt, ToolApprovalStatus status) {
        UUID approvalId = UUID.fromString(id);
        boolean pending = status == ToolApprovalStatus.PENDING;
        var firstDecision = pending || status == ToolApprovalStatus.EXPIRED ? null
                : status == ToolApprovalStatus.DENIED ? ToolApprovalDecision.DENY : ToolApprovalDecision.APPROVE;
        var first = new ToolApprovalItem(UUID.randomUUID(), approvalId, "call-1", tool, "history_tool", ToolRiskLevel.HIGH,
                "已脱敏摘要", "a".repeat(64), ToolApprovalPolicy.EACH_CALL, 1, firstDecision);
        var second = new ToolApprovalItem(UUID.randomUUID(), approvalId, "call-2", tool, "history_tool", ToolRiskLevel.HIGH,
                "第二项摘要", "b".repeat(64), ToolApprovalPolicy.EACH_CALL, 1,
                status == ToolApprovalStatus.PARTIALLY_APPROVED ? ToolApprovalDecision.DENY : firstDecision);
        // 数据库明细按 UUID 字符串排序，测试也使用同一顺序，避免随机 UUID 改变预期。
        var items = Stream.of(first, second).sorted(java.util.Comparator.comparing(item -> item.id().toString())).toList();
        return new ToolApprovalRequest(approvalId, tenant, agent, conversation, run, "tester", "发起人", status,
                decidedAt.plusSeconds(900), pending ? 0 : 1, run.toString(), decidedAt.minusSeconds(10), decidedAt,
                pending ? null : "tester", pending ? null : "审批人", pending ? null : decidedAt, items);
    }
}
