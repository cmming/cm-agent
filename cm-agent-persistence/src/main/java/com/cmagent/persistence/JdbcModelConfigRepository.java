package com.cmagent.persistence;

import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.repository.ModelConfigRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/** 使用 JDBC 在租户边界内管理模型供应商配置及其加密凭据。 */
public class JdbcModelConfigRepository implements ModelConfigRepository {
    private static final String CREDENTIAL_PLACEHOLDER = "not-configured";
    private final JdbcClient jdbcClient;

    /**
     * 创建模型配置仓储。
     *
     * @param jdbcClient 执行参数化 SQL 的 JDBC 客户端
     */
    public JdbcModelConfigRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public ModelConfig save(ModelConfig modelConfig) {
        return save(modelConfig, CREDENTIAL_PLACEHOLDER);
    }

    @Override
    public ModelConfig save(ModelConfig modelConfig, String encryptedApiKey) {
        jdbcClient.sql("""
                        INSERT INTO model_configs (
                            id, tenant_id, provider_type, display_name, base_url, model_name,
                            encrypted_api_key, enabled, created_at
                        ) VALUES (
                            :id, :tenantId, :providerType, :displayName, :baseUrl, :modelName,
                            :credentialPlaceholder, :enabled, :createdAt
                        )
                        """)
                .param("id", modelConfig.id().toString())
                .param("tenantId", modelConfig.tenantId().toString())
                .param("providerType", modelConfig.providerType().name())
                .param("displayName", modelConfig.displayName())
                .param("baseUrl", modelConfig.baseUrl())
                .param("modelName", modelConfig.modelName())
                .param("credentialPlaceholder", encryptedApiKey)
                .param("enabled", modelConfig.enabled())
                .param("createdAt", Timestamp.from(Instant.now()))
                .update();
        return modelConfig;
    }

    @Override
    public ModelConfig update(ModelConfig modelConfig) {
        return update(modelConfig, null);
    }

    @Override
    public ModelConfig update(ModelConfig modelConfig, String encryptedApiKey) {
        int updated = jdbcClient.sql("""
                        UPDATE model_configs
                        SET provider_type = :providerType,
                            display_name = :displayName,
                            base_url = :baseUrl,
                            model_name = :modelName,
                            enabled = :enabled,
                            encrypted_api_key = COALESCE(:encryptedApiKey, encrypted_api_key)
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("providerType", modelConfig.providerType().name())
                .param("displayName", modelConfig.displayName())
                .param("baseUrl", modelConfig.baseUrl())
                .param("modelName", modelConfig.modelName())
                .param("enabled", modelConfig.enabled())
                .param("encryptedApiKey", encryptedApiKey)
                .param("tenantId", modelConfig.tenantId().toString())
                .param("id", modelConfig.id().toString())
                .update();
        if (updated == 0) {
            throw new NoSuchElementException("模型配置不存在");
        }
        return modelConfig;
    }

    @Override
    /**
     * 查询租户内指定模型配置，不读取或返回模型密钥。
     *
     * @param tenantId 租户标识
     * @param modelConfigId 模型配置标识
     * @return 匹配的模型配置
     */
    public Optional<ModelConfig> findByTenantAndId(UUID tenantId, UUID modelConfigId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, provider_type, display_name, base_url, model_name, enabled
                        FROM model_configs
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.toString())
                .param("id", modelConfigId.toString())
                .query(this::mapModelConfig)
                .optional();
    }

    @Override
    public Optional<String> findEncryptedApiKeyByTenantAndId(UUID tenantId, UUID modelConfigId) {
        return jdbcClient.sql("""
                        SELECT encrypted_api_key
                        FROM model_configs
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.toString())
                .param("id", modelConfigId.toString())
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<ModelConfig> findByTenantAndIdForUpdate(UUID tenantId, UUID modelConfigId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, provider_type, display_name, base_url, model_name, enabled
                        FROM model_configs
                        WHERE tenant_id = :tenantId AND id = :id
                        FOR UPDATE
                        """)
                .param("tenantId", tenantId.toString())
                .param("id", modelConfigId.toString())
                .query(this::mapModelConfig)
                .optional();
    }

    @Override
    public List<ModelConfig> listByTenant(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, provider_type, display_name, base_url, model_name, enabled
                        FROM model_configs
                        WHERE tenant_id = :tenantId
                        ORDER BY display_name, id
                        """)
                .param("tenantId", tenantId.toString())
                .query(this::mapModelConfig)
                .list();
    }

    @Override
    public boolean isReferencedByAgent(UUID tenantId, UUID modelConfigId) {
        Long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM agent_definitions
                        WHERE tenant_id = :tenantId AND model_provider_id = :id
                        """)
                .param("tenantId", tenantId.toString())
                .param("id", modelConfigId.toString())
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    @Override
    public boolean delete(UUID tenantId, UUID modelConfigId) {
        return jdbcClient.sql("""
                        DELETE FROM model_configs
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.toString())
                .param("id", modelConfigId.toString())
                .update() > 0;
    }

    private ModelConfig mapModelConfig(ResultSet rs, int rowNum) throws SQLException {
        return new ModelConfig(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                ModelProviderType.valueOf(rs.getString("provider_type")),
                rs.getString("display_name"),
                rs.getString("base_url"),
                rs.getString("model_name"),
                rs.getBoolean("enabled")
        );
    }
}
