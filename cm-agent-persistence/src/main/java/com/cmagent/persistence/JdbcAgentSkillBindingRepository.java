package com.cmagent.persistence;

import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.repository.AgentSkillBindingRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 JDBC 管理 Agent 与技能绑定，并以 Agent 行锁串行化上限检查和变更。 */
public class JdbcAgentSkillBindingRepository implements AgentSkillBindingRepository {
    private final JdbcClient jdbcClient;

    /** @param jdbcClient 执行具名参数 SQL 的客户端 */
    public JdbcAgentSkillBindingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
    }

    @Override
    public List<AgentSkillBinding> list(UUID tenantId, UUID agentId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, agent_id, skill_id, bound_by, created_at
                        FROM agent_skill_bindings
                        WHERE tenant_id = :tenantId AND agent_id = :agentId
                        ORDER BY created_at, id
                        """)
                .param("tenantId", tenantId.toString()).param("agentId", agentId.toString())
                .query(this::map).list();
    }

    @Override
    public Optional<AgentSkillBinding> find(UUID tenantId, UUID agentId, UUID skillId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, agent_id, skill_id, bound_by, created_at
                        FROM agent_skill_bindings
                        WHERE tenant_id = :tenantId AND agent_id = :agentId AND skill_id = :skillId
                        """)
                .param("tenantId", tenantId.toString()).param("agentId", agentId.toString())
                .param("skillId", skillId.toString()).query(this::map).optional();
    }

    @Override
    public void insert(AgentSkillBinding binding) {
        Objects.requireNonNull(binding, "binding 不能为空");
        jdbcClient.sql("""
                        INSERT INTO agent_skill_bindings (
                            id, tenant_id, agent_id, skill_id, bound_by, created_at
                        ) VALUES (:id, :tenantId, :agentId, :skillId, :boundBy, :createdAt)
                        """)
                .param("id", binding.id().toString())
                .param("tenantId", binding.tenantId().toString())
                .param("agentId", binding.agentId().toString())
                .param("skillId", binding.skillId().toString())
                .param("boundBy", binding.boundBy())
                .param("createdAt", Timestamp.from(binding.createdAt()))
                .update();
    }

    @Override
    public boolean delete(UUID tenantId, UUID agentId, UUID skillId) {
        return jdbcClient.sql("""
                        DELETE FROM agent_skill_bindings
                        WHERE tenant_id = :tenantId AND agent_id = :agentId AND skill_id = :skillId
                        """)
                .param("tenantId", tenantId.toString()).param("agentId", agentId.toString())
                .param("skillId", skillId.toString()).update() == 1;
    }

    @Override
    public long countBySkill(UUID tenantId, UUID skillId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM agent_skill_bindings
                        WHERE tenant_id = :tenantId AND skill_id = :skillId
                        """)
                .param("tenantId", tenantId.toString()).param("skillId", skillId.toString())
                .query(Long.class).single();
    }

    @Override
    public void lockAgent(UUID tenantId, UUID agentId) {
        // 行锁必须先于绑定计数、删除或插入；所有调用路径保持 Agent → Skill 的锁顺序。
        Boolean found = jdbcClient.sql("""
                        SELECT TRUE FROM agent_definitions
                        WHERE tenant_id = :tenantId AND id = :agentId FOR UPDATE
                        """)
                .param("tenantId", tenantId.toString()).param("agentId", agentId.toString())
                .query(Boolean.class).optional().orElse(false);
        if (!found) {
            throw new NoSuchElementException("Agent 不存在");
        }
    }

    private AgentSkillBinding map(ResultSet resultSet, int rowNum) throws SQLException {
        return new AgentSkillBinding(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("agent_id")),
                UUID.fromString(resultSet.getString("skill_id")),
                resultSet.getString("bound_by"),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
