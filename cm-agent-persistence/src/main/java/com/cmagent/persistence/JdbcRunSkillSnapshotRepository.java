package com.cmagent.persistence;

import com.cmagent.core.domain.RunSkillSnapshot;
import com.cmagent.core.domain.SkillSnapshotRef;
import com.cmagent.core.repository.RunSkillSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 JDBC 保存每个 Run 唯一且不可变的技能授权快照。 */
public class JdbcRunSkillSnapshotRepository implements RunSkillSnapshotRepository {
    private static final TypeReference<List<SkillSnapshotRef>> SKILLS_TYPE = new TypeReference<>() { };
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbcClient 执行具名参数 SQL 的客户端
     * @param objectMapper 序列化固定版本、绑定和访问纪元引用
     */
    public JdbcRunSkillSnapshotRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    @Override
    public void insert(RunSkillSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        jdbcClient.sql("""
                        INSERT INTO run_skill_snapshots (
                            tenant_id, run_id, agent_id, format_version, skills_json, created_at
                        ) VALUES (:tenantId, :runId, :agentId, :formatVersion, :skillsJson, :createdAt)
                        """)
                .param("tenantId", snapshot.tenantId().toString())
                .param("runId", snapshot.runId().toString())
                .param("agentId", snapshot.agentId().toString())
                .param("formatVersion", snapshot.formatVersion())
                .param("skillsJson", writeSkills(snapshot.skills()))
                .param("createdAt", Timestamp.from(snapshot.createdAt()))
                .update();
    }

    @Override
    public Optional<RunSkillSnapshot> find(UUID tenantId, UUID runId) {
        return query(tenantId, runId, false);
    }

    @Override
    public RunSkillSnapshot lock(UUID tenantId, UUID runId) {
        // 恢复与读取先锁快照，再锁 Agent 和按 UUID 排序的技能定义，避免交叉路径死锁。
        return query(tenantId, runId, true)
                .orElseThrow(() -> new NoSuchElementException("运行技能快照不存在"));
    }

    private Optional<RunSkillSnapshot> query(UUID tenantId, UUID runId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbcClient.sql("""
                        SELECT tenant_id, run_id, agent_id, format_version, skills_json, created_at
                        FROM run_skill_snapshots
                        WHERE tenant_id = :tenantId AND run_id = :runId
                        """ + suffix)
                .param("tenantId", tenantId.toString()).param("runId", runId.toString())
                .query(this::map).optional();
    }

    private RunSkillSnapshot map(ResultSet resultSet, int rowNum) throws SQLException {
        return new RunSkillSnapshot(
                UUID.fromString(resultSet.getString("tenant_id")),
                UUID.fromString(resultSet.getString("run_id")),
                UUID.fromString(resultSet.getString("agent_id")),
                resultSet.getInt("format_version"),
                readSkills(resultSet.getString("skills_json")),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private String writeSkills(List<SkillSnapshotRef> skills) {
        try {
            return objectMapper.writeValueAsString(skills);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("运行技能快照序列化失败", exception);
        }
    }

    private List<SkillSnapshotRef> readSkills(String json) {
        try {
            return objectMapper.readValue(json, SKILLS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("持久化运行技能快照无法解析", exception);
        }
    }
}
