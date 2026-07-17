package com.cmagent.persistence;

import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.repository.ModelConfigRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;
import java.util.UUID;

public class JdbcModelConfigRepository implements ModelConfigRepository {
    private final JdbcClient jdbcClient;

    public JdbcModelConfigRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<ModelConfig> findByTenantAndId(UUID tenantId, UUID modelConfigId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, provider_type, display_name, base_url, model_name, enabled
                        FROM model_configs
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.toString())
                .param("id", modelConfigId.toString())
                .query((rs, rowNum) -> new ModelConfig(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("tenant_id")),
                        ModelProviderType.valueOf(rs.getString("provider_type")),
                        rs.getString("display_name"),
                        rs.getString("base_url"),
                        rs.getString("model_name"),
                        rs.getBoolean("enabled")))
                .optional();
    }
}
