package com.cmagent.persistence;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 JDBC 持久化 Agent 定义及其关联工具 ID 集合。 */
public class JdbcAgentDefinitionRepository implements AgentDefinitionRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建 Agent 定义仓储。
     *
     * @param jdbcClient 执行参数化 SQL 的 JDBC 客户端
     * @param objectMapper 序列化和反序列化工具 ID 集合的 JSON 组件
     * @param transactionTemplate 保证关联工具读改写原子性的事务模板
     */
    public JdbcAgentDefinitionRepository(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate 不能为空");
    }

    @Override
    /**
     * 新增或更新 Agent 定义，并将工具 ID 集合持久化为 JSON。
     *
     * @param agent 待保存的 Agent 定义
     * @return 已保存的 Agent 定义
     */
    public AgentDefinition save(AgentDefinition agent) {
        Instant now = Instant.now();
        jdbcClient.sql("""
                        INSERT INTO agent_definitions (
                            id,
                            tenant_id,
                            name,
                            description,
                            system_prompt,
                            model_provider_id,
                            model_name,
                            temperature,
                            max_iterations,
                            enabled,
                            tool_ids_json,
                            created_by,
                            updated_by,
                            created_at,
                            updated_at
                        ) VALUES (
                            :id,
                            :tenantId,
                            :name,
                            :description,
                            :systemPrompt,
                            :modelProviderId,
                            :modelName,
                            :temperature,
                            :maxIterations,
                            :enabled,
                            :toolIdsJson,
                            :createdBy,
                            :updatedBy,
                            :createdAt,
                            :updatedAt
                        )
                        """)
                .param("id", agent.id().toString())
                .param("tenantId", agent.tenantId().toString())
                .param("name", agent.name())
                .param("description", agent.description())
                .param("systemPrompt", agent.systemPrompt())
                .param("modelProviderId", agent.modelProviderId().toString())
                .param("modelName", agent.modelName())
                .param("temperature", agent.temperature())
                .param("maxIterations", agent.maxIterations())
                .param("enabled", agent.enabled())
                .param("toolIdsJson", writeToolIds(agent.toolIds()))
                .param("createdBy", agent.createdBy())
                .param("updatedBy", agent.updatedBy())
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
        return agent;
    }

    @Override
    /**
     * 更新 Agent 可编辑字段。工具关联由专用的增删关联方法维护，避免编辑表单
     * 覆盖并发授权结果；创建人与创建时间同样不可由更新请求改写。
     *
     * @param agent 包含最新可编辑字段的 Agent 定义
     * @return 更新后的 Agent 定义
     */
    public AgentDefinition update(AgentDefinition agent) {
        int updated = jdbcClient.sql("""
                        UPDATE agent_definitions
                        SET name = :name,
                            system_prompt = :systemPrompt,
                            model_provider_id = :modelProviderId,
                            model_name = :modelName,
                            enabled = :enabled,
                            updated_by = :updatedBy,
                            updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("name", agent.name())
                .param("systemPrompt", agent.systemPrompt())
                .param("modelProviderId", agent.modelProviderId().toString())
                .param("modelName", agent.modelName())
                .param("enabled", agent.enabled())
                .param("updatedBy", agent.updatedBy())
                .param("updatedAt", Timestamp.from(Instant.now()))
                .param("tenantId", agent.tenantId().toString())
                .param("id", agent.id().toString())
                .update();
        if (updated == 0) {
            throw new NoSuchElementException("Agent 不存在");
        }
        return agent;
    }

    @Override
    /**
     * 在租户边界内查询指定 Agent。
     *
     * @param tenantId 租户标识
     * @param agentId Agent 标识
     * @return 匹配的 Agent 定义
     */
    public Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
        return jdbcClient.sql("""
                        SELECT
                            id,
                            tenant_id,
                            name,
                            description,
                            system_prompt,
                            model_provider_id,
                            model_name,
                            temperature,
                            max_iterations,
                            enabled,
                            tool_ids_json,
                            created_by,
                            updated_by
                        FROM agent_definitions
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.toString())
                .param("id", agentId.toString())
                .query(this::mapAgent)
                .optional();
    }

    @Override
    /**
     * 查询指定租户的全部 Agent 定义。
     *
     * @param tenantId 租户标识
     * @return Agent 定义列表
     */
    public List<AgentDefinition> listByTenant(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT
                            id,
                            tenant_id,
                            name,
                            description,
                            system_prompt,
                            model_provider_id,
                            model_name,
                            temperature,
                            max_iterations,
                            enabled,
                            tool_ids_json,
                            created_by,
                            updated_by
                        FROM agent_definitions
                        WHERE tenant_id = :tenantId
                        ORDER BY name ASC, id ASC
                        """)
                .param("tenantId", tenantId.toString())
                .query(this::mapAgent)
                .list();
    }

    @Override
    /**
     * 判断 Agent 是否已产生会话或运行记录。两类记录都保留外键和审计价值，
     * 管理端删除时必须先显式拒绝，不能依赖数据库外键异常作为用户提示。
     *
     * @param tenantId 租户标识
     * @param agentId Agent 标识
     * @return 已有关联历史时为 {@code true}
     */
    public boolean hasUsageHistory(UUID tenantId, UUID agentId) {
        Boolean exists = jdbcClient.sql("""
                        SELECT CASE WHEN EXISTS (
                            SELECT 1 FROM conversations
                            WHERE tenant_id = :tenantId AND agent_id = :agentId
                        ) OR EXISTS (
                            SELECT 1 FROM runs
                            WHERE tenant_id = :tenantId AND agent_id = :agentId
                        ) THEN true ELSE false END
                        """)
                .param("tenantId", tenantId.toString())
                .param("agentId", agentId.toString())
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    @Override
    /**
     * 删除无历史依赖的 Agent 定义。工具授权需由调用方在同一事务内先行删除，
     * 以满足 {@code tool_grants} 对 Agent 的复合外键约束。
     *
     * @param tenantId 租户标识
     * @param agentId Agent 标识
     * @return 实际删除记录时为 {@code true}
     */
    public boolean delete(UUID tenantId, UUID agentId) {
        return jdbcClient.sql("""
                        DELETE FROM agent_definitions
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.toString())
                .param("id", agentId.toString())
                .update() > 0;
    }

    @Override
    /**
     * 在事务中将工具 ID 加入 Agent 的关联集合。
     *
     * @param tenantId 租户标识
     * @param agentId Agent 标识
     * @param toolId 待关联的工具标识
     * @return 更新后的 Agent 定义
     */
    public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
        return Objects.requireNonNull(transactionTemplate.execute(
                status -> mutateToolIds(tenantId, agentId, toolId, true)
        ), "事务未返回 Agent");
    }

    @Override
    /**
     * 在事务中从 Agent 的关联集合移除工具 ID。
     *
     * @param tenantId 租户标识
     * @param agentId Agent 标识
     * @param toolId 待解除关联的工具标识
     * @return 更新后的 Agent 定义
     */
    public AgentDefinition removeToolFromAgent(UUID tenantId, UUID agentId, UUID toolId) {
        return Objects.requireNonNull(transactionTemplate.execute(
                status -> mutateToolIds(tenantId, agentId, toolId, false)
        ), "事务未返回 Agent");
    }

    /**
     * 加锁读取 Agent 后修改工具 ID 集合，并在同一事务中写回。
     *
     * @param tenantId 租户标识
     * @param agentId Agent 标识
     * @param toolId 待增加或移除的工具标识
     * @param add {@code true} 表示增加，{@code false} 表示移除
     * @return 更新后的 Agent 定义
     */
    private AgentDefinition mutateToolIds(UUID tenantId, UUID agentId, UUID toolId, boolean add) {
        AgentDefinition agent = findByTenantAndIdForUpdate(tenantId, agentId)
                .orElseThrow(() -> new NoSuchElementException("Agent 不存在"));
        if (add == agent.toolIds().contains(toolId)) {
            return agent;
        }

        List<UUID> toolIds;
        if (add) {
            toolIds = new ArrayList<>(agent.toolIds());
            toolIds.add(toolId);
        } else {
            toolIds = agent.toolIds().stream()
                    .filter(id -> !id.equals(toolId))
                    .toList();
        }
        AgentDefinition updated = new AgentDefinition(
                agent.id(),
                agent.tenantId(),
                agent.name(),
                agent.description(),
                agent.systemPrompt(),
                agent.modelProviderId(),
                agent.modelName(),
                agent.temperature(),
                agent.maxIterations(),
                agent.enabled(),
                toolIds,
                agent.createdBy(),
                agent.updatedBy()
        );

        jdbcClient.sql("""
                        UPDATE agent_definitions
                        SET tool_ids_json = :toolIdsJson,
                            updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("toolIdsJson", writeToolIds(updated.toolIds()))
                .param("updatedAt", Timestamp.from(Instant.now()))
                .param("tenantId", tenantId.toString())
                .param("id", agentId.toString())
                .update();
        return updated;
    }

    /**
     * 使用行锁读取租户内 Agent，避免并发关联操作丢失更新。
     *
     * @param tenantId 租户标识
     * @param agentId Agent 标识
     * @return 匹配并已锁定的 Agent 定义
     */
    private Optional<AgentDefinition> findByTenantAndIdForUpdate(UUID tenantId, UUID agentId) {
        return jdbcClient.sql("""
                        SELECT
                            id,
                            tenant_id,
                            name,
                            description,
                            system_prompt,
                            model_provider_id,
                            model_name,
                            temperature,
                            max_iterations,
                            enabled,
                            tool_ids_json,
                            created_by,
                            updated_by
                        FROM agent_definitions
                        WHERE tenant_id = :tenantId AND id = :id
                        FOR UPDATE
                        """)
                .param("tenantId", tenantId.toString())
                .param("id", agentId.toString())
                .query(this::mapAgent)
                .optional();
    }

    /**
     * 将当前结果集行及工具 ID JSON 转换为 Agent 领域对象。
     *
     * @param rs 已定位到当前行的查询结果
     * @param rowNum 当前行序号，仅满足 JDBC 行映射器签名
     * @return Agent 定义
     * @throws SQLException 读取列值失败时抛出
     */
    private AgentDefinition mapAgent(ResultSet rs, int rowNum) throws SQLException {
        return new AgentDefinition(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("system_prompt"),
                UUID.fromString(rs.getString("model_provider_id")),
                rs.getString("model_name"),
                rs.getDouble("temperature"),
                rs.getInt("max_iterations"),
                rs.getBoolean("enabled"),
                readToolIds(rs.getString("tool_ids_json")),
                rs.getString("created_by"),
                rs.getString("updated_by")
        );
    }

    /**
     * 将工具 ID 列表序列化为数据库 JSON 文本。
     *
     * @param toolIds 工具标识列表
     * @return JSON 文本
     */
    private String writeToolIds(List<UUID> toolIds) {
        try {
            List<String> values = toolIds.stream().map(UUID::toString).toList();
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("toolIds 序列化失败", e);
        }
    }

    /**
     * 将数据库 JSON 文本还原为工具 ID 列表。
     *
     * @param json 工具 ID 数组的 JSON 文本
     * @return 工具标识列表
     */
    private List<UUID> readToolIds(String json) {
        try {
            String[] values = objectMapper.readValue(json, String[].class);
            return Arrays.stream(values).map(UUID::fromString).toList();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("toolIds 反序列化失败", e);
        }
    }
}
