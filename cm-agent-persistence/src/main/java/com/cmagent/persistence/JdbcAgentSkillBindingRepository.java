package com.cmagent.persistence;

import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.domain.SkillBindingMode;
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
                        SELECT id, tenant_id, agent_id, skill_id, resolution_mode, pinned_version_id, revision,
                               bound_by, updated_by, created_at, updated_at
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
                        SELECT id, tenant_id, agent_id, skill_id, resolution_mode, pinned_version_id, revision,
                               bound_by, updated_by, created_at, updated_at
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
                            id, tenant_id, agent_id, skill_id, resolution_mode, pinned_version_id, revision,
                            bound_by, updated_by, created_at, updated_at
                        ) VALUES (
                            :id, :tenantId, :agentId, :skillId, :mode, :pinnedVersionId, :revision,
                            :boundBy, :updatedBy, :createdAt, :updatedAt
                        )
                        """)
                .param("id", binding.id().toString())
                .param("tenantId", binding.tenantId().toString())
                .param("agentId", binding.agentId().toString())
                .param("skillId", binding.skillId().toString())
                .param("mode", binding.mode().name())
                .param("pinnedVersionId", binding.pinnedVersionId() == null ? null : binding.pinnedVersionId().toString())
                .param("revision", binding.revision())
                .param("boundBy", binding.boundBy())
                .param("updatedBy", binding.updatedBy())
                .param("createdAt", Timestamp.from(binding.createdAt()))
                .param("updatedAt", Timestamp.from(binding.updatedAt()))
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

    @Override
    public boolean updateStrategy(AgentSkillBinding binding, long expectedRevision) {
        return jdbcClient.sql("""
                        UPDATE agent_skill_bindings
                        SET resolution_mode = :mode, pinned_version_id = :pinnedVersionId,
                            revision = revision + 1, updated_by = :updatedBy, updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND agent_id = :agentId AND skill_id = :skillId
                          AND revision = :expectedRevision
                        """)
                .param("mode", binding.mode().name())
                .param("pinnedVersionId", binding.pinnedVersionId() == null ? null : binding.pinnedVersionId().toString())
                .param("updatedBy", binding.updatedBy())
                .param("updatedAt", Timestamp.from(binding.updatedAt()))
                .param("tenantId", binding.tenantId().toString())
                .param("agentId", binding.agentId().toString())
                .param("skillId", binding.skillId().toString())
                .param("expectedRevision", expectedRevision).update() == 1;
    }

    private AgentSkillBinding map(ResultSet resultSet, int rowNum) throws SQLException {

        String pinned = resultSet.getString("pinned_version_id");
        AgentSkillBinding binding = new AgentSkillBinding(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("agent_id")),
                UUID.fromString(resultSet.getString("skill_id")),
                SkillBindingMode.valueOf(resultSet.getString("resolution_mode")),
                pinned == null ? null : UUID.fromString(pinned),
                resultSet.getLong("revision"),
                resultSet.getString("bound_by"),
                resultSet.getString("updated_by"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
        return binding;
    }
}
