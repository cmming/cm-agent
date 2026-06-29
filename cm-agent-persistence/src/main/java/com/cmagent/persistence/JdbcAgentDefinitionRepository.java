package com.cmagent.persistence;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public class JdbcAgentDefinitionRepository implements AgentDefinitionRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcAgentDefinitionRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
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
    public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
        AgentDefinition agent = findByTenantAndId(tenantId, agentId)
                .orElseThrow(() -> new NoSuchElementException("Agent 不存在"));
        if (agent.toolIds().contains(toolId)) {
            return agent;
        }

        List<UUID> toolIds = new ArrayList<>(agent.toolIds());
        toolIds.add(toolId);
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

    private String writeToolIds(List<UUID> toolIds) {
        try {
            List<String> values = toolIds.stream().map(UUID::toString).toList();
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("toolIds 序列化失败", e);
        }
    }

    private List<UUID> readToolIds(String json) {
        try {
            String[] values = objectMapper.readValue(json, String[].class);
            return Arrays.stream(values).map(UUID::fromString).toList();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("toolIds 反序列化失败", e);
        }
    }
}
