package com.cmagent.server.runtime;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.domain.ToolApprovalStatus;
import com.cmagent.server.audit.AuditPersistenceException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 仅在 Rocky 容器环境重跑实际 Harness 审批、状态和停止合同。
 *
 * <p>使用 Testcontainers 专属数据库，默认 PostgreSQL 16；系统属性
 * {@code cm-agent.test.database=mysql} 选择 MySQL 8.4。动态连接凭据不落盘或输出。</p>
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HarnessApprovalJdbcFlowTest extends HarnessApprovalFlowTest {
    /** 测试独占临时库，由 Testcontainers 负责关闭，禁止连接部署者数据库。 */
    @Container
    static final JdbcDatabaseContainer<?> database = "mysql".equals(System.getProperty("cm-agent.test.database"))
            ? new MySQLContainer<>("mysql:8.4") : new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("cm-agent.persistence.mode", () -> "jdbc");
        properties.add("cm-agent.persistence.jdbc.url", database::getJdbcUrl);
        properties.add("cm-agent.persistence.jdbc.username", database::getUsername);
        properties.add("cm-agent.persistence.jdbc.password", database::getPassword);
        properties.add("cm-agent.persistence.jdbc.driver-class-name", database::getDriverClassName);
    }

    @Test
    void 决定审计失败回滚批准与Run迁移并保留原加密状态() {
        var pending = waiting();
        doThrow(new AuditPersistenceException("审计不可用", new IllegalStateException("合成失败")))
                .when(audit).append(any(), any(), eq("TOOL_APPROVAL_DECIDE"), any(), any(), any(), any());
        assertThatThrownBy(() -> decide(pending, ToolApprovalDecision.APPROVE)).isInstanceOf(AuditPersistenceException.class);
        assertThat(approvals.get(owner, agent, conversation, pending.approvalId()).status()).isEqualTo(ToolApprovalStatus.PENDING);
        assertThat(runs.findByTenantAndAgentAndId(owner.tenantId(), agent, pending.runId()).orElseThrow().status())
                .isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(checkpoints.exists(owner.tenantId(), owner.tenantId() + ":" + owner.principalId(), pending.runId().toString())).isTrue();
        reset(audit);
        decide(pending, ToolApprovalDecision.DENY);
        assertNoCheckpoint(pending);
    }

    @Test
    void 等待停止审计失败回滚Run审批和检查点后允许重试() {
        var pending = waiting();
        doThrow(new AuditPersistenceException("审计不可用", new IllegalStateException("合成失败")))
                .when(audit).append(any(), any(), eq("TOOL_APPROVAL_CANCEL"), any(), any(), any(), any());
        assertThatThrownBy(() -> conversations.cancel(owner, agent, conversation, pending.runId()))
                .isInstanceOf(AuditPersistenceException.class);
        assertThat(approvals.get(owner, agent, conversation, pending.approvalId()).status()).isEqualTo(ToolApprovalStatus.PENDING);
        assertThat(runs.findByTenantAndAgentAndId(owner.tenantId(), agent, pending.runId()).orElseThrow().status())
                .isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(checkpoints.exists(owner.tenantId(), owner.tenantId() + ":" + owner.principalId(), pending.runId().toString())).isTrue();
        reset(audit);
        assertThat(conversations.cancel(owner, agent, conversation, pending.runId()).status()).isEqualTo(RunStatus.CANCELLED);
        assertNoCheckpoint(pending);
    }
}
