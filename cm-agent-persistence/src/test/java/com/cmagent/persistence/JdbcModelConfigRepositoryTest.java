package com.cmagent.persistence;

import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcModelConfigRepositoryTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MODEL_A = UUID.fromString("40000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcModelConfigRepository repository;

    @BeforeEach
    /**
     * 准备每个测试用例共享的前置数据。
     */
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        CmAgentFlyway.configure(dataSource)
                .cleanDisabled(false).load().clean();
        CmAgentFlyway.configure(dataSource).load().migrate();
        seedData(dataSource);
        repository = new JdbcModelConfigRepository(JdbcClient.create(dataSource));
    }

    @Test
    /**
     * 验证查询能够找到 {@code OnlyModelConfigOwnedByTenant}。
     */
    void findsOnlyModelConfigOwnedByTenant() {
        ModelConfig own = repository.findByTenantAndId(TENANT_A, MODEL_A).orElseThrow();

        assertThat(own.providerType()).isEqualTo(ModelProviderType.OPENAI_COMPATIBLE);
        assertThat(own.baseUrl()).isEqualTo("https://model-a.invalid/v1");
        assertThat(repository.findByTenantAndId(TENANT_B, MODEL_A)).isEmpty();
    }

    @Test
    void 创建更新列出和删除均保持租户边界且可保存加密凭据() {
        UUID id = UUID.fromString("40000000-0000-0000-0000-000000000002");
        ModelConfig created = repository.save(new ModelConfig(
                id, TENANT_A, ModelProviderType.DASHSCOPE_NATIVE,
                "新模型", "https://dashscope.example.test/api", "qwen-plus", true
        ), "v1:test-iv:test-ciphertext");

        assertThat(repository.listByTenant(TENANT_A)).extracting(ModelConfig::id)
                .containsExactlyInAnyOrder(MODEL_A, id);
        assertThat(repository.listByTenant(TENANT_B)).isEmpty();

        ModelConfig updated = repository.update(new ModelConfig(
                created.id(), created.tenantId(), ModelProviderType.OPENAI_COMPATIBLE,
                "更新模型", "https://models.example.test/v1", "qwen-max", false
        ), "v1:rotated-iv:rotated-ciphertext");
        assertThat(updated.enabled()).isFalse();
        assertThat(repository.findByTenantAndId(TENANT_A, id).orElseThrow().displayName()).isEqualTo("更新模型");
        assertThat(repository.isReferencedByAgent(TENANT_A, id)).isFalse();

        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        assertThat(repository.findEncryptedApiKeyByTenantAndId(TENANT_A, id))
                .contains("v1:rotated-iv:rotated-ciphertext");
        assertThat(repository.findEncryptedApiKeyByTenantAndId(TENANT_B, id)).isEmpty();

        assertThat(repository.delete(TENANT_B, id)).isFalse();
        assertThat(repository.delete(TENANT_A, id)).isTrue();
        assertThat(repository.findByTenantAndId(TENANT_A, id)).isEmpty();
    }

    /**
     * 验证或支持 {@code seedData} 所描述的测试场景。
     *
     * @param dataSource 测试数据源
     */
    private static void seedData(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        Timestamp now = Timestamp.from(Instant.parse("2026-07-16T00:00:00Z"));
        insertTenant(jdbc, TENANT_A, "tenant-a", now);
        insertTenant(jdbc, TENANT_B, "tenant-b", now);
        jdbc.sql("""
                        INSERT INTO model_configs (
                            id, tenant_id, provider_type, display_name, base_url, model_name,
                            encrypted_api_key, enabled, created_at
                        ) VALUES (
                            :id, :tenantId, 'OPENAI_COMPATIBLE', '模型A', 'https://model-a.invalid/v1',
                            'model-a', 'not-configured', true, :createdAt
                        )
                        """)
                .param("id", MODEL_A.toString())
                .param("tenantId", TENANT_A.toString())
                .param("createdAt", now)
                .update();
    }

    /**
     * 验证或支持 {@code insertTenant} 所描述的测试场景。
     *
     * @param jdbc 测试辅助方法使用的 jdbc 参数
     * @param id 测试辅助方法使用的 id 参数
     * @param code 测试辅助方法使用的 code 参数
     * @param now 测试辅助方法使用的 now 参数
     */
    private static void insertTenant(JdbcClient jdbc, UUID id, String code, Timestamp now) {
        jdbc.sql("""
                        INSERT INTO tenants (id, code, name, enabled, created_at)
                        VALUES (:id, :code, :name, true, :createdAt)
                        """)
                .param("id", id.toString())
                .param("code", code)
                .param("name", code)
                .param("createdAt", now)
                .update();
    }
}
