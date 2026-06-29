package com.cmagent.server.config;

import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.persistence.JdbcAgentDefinitionRepository;
import com.cmagent.persistence.JdbcToolDefinitionRepository;
import com.cmagent.persistence.JdbcToolGrantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.Instant;

@Configuration
@ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "jdbc")
public class JdbcPersistenceConfiguration {
    private static final String DEFAULT_TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String DEFAULT_MODEL_PROVIDER_ID = "00000000-0000-0000-0000-000000000301";

    @Bean(destroyMethod = "close")
    DataSource cmAgentDataSource(CmAgentPersistenceProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.getJdbc().getUrl());
        dataSource.setUsername(properties.getJdbc().getUsername());
        dataSource.setPassword(properties.getJdbc().getPassword());
        if (!properties.getJdbc().getDriverClassName().isBlank()) {
            dataSource.setDriverClassName(properties.getJdbc().getDriverClassName());
        }
        dataSource.setPoolName("cm-agent-jdbc");
        return dataSource;
    }

    @Bean
    Flyway cmAgentFlyway(DataSource cmAgentDataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(cmAgentDataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        return flyway;
    }

    @Bean
    JdbcClient cmAgentJdbcClient(DataSource cmAgentDataSource, Flyway cmAgentFlyway) {
        return JdbcClient.create(cmAgentDataSource);
    }

    @Bean
    AgentDefinitionRepository jdbcAgentDefinitionRepository(JdbcClient cmAgentJdbcClient) {
        return new JdbcAgentDefinitionRepository(cmAgentJdbcClient, new ObjectMapper());
    }

    @Bean
    ToolDefinitionRepository jdbcToolDefinitionRepository(JdbcClient cmAgentJdbcClient) {
        return new JdbcToolDefinitionRepository(cmAgentJdbcClient);
    }

    @Bean
    ToolGrantRepository jdbcToolGrantRepository(JdbcClient cmAgentJdbcClient) {
        return new JdbcToolGrantRepository(cmAgentJdbcClient);
    }

    @Bean
    ApplicationRunner defaultTenantDataInitializer(JdbcClient cmAgentJdbcClient, Flyway cmAgentFlyway) {
        return args -> {
            Instant now = Instant.now();
            cmAgentJdbcClient.sql("""
                            INSERT INTO tenants (id, code, name, enabled, created_at)
                            SELECT :id, :code, :name, true, :createdAt
                            WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE id = :id)
                            """)
                    .param("id", DEFAULT_TENANT_ID)
                    .param("code", "default")
                    .param("name", "默认租户")
                    .param("createdAt", now)
                    .update();

            cmAgentJdbcClient.sql("""
                            INSERT INTO model_configs (
                                id,
                                tenant_id,
                                provider_type,
                                display_name,
                                base_url,
                                model_name,
                                encrypted_api_key,
                                enabled,
                                created_at
                            )
                            SELECT :id,
                                   :tenantId,
                                   'OPENAI_COMPATIBLE',
                                   '默认模型',
                                   'https://example.invalid',
                                   'qwen-max',
                                   'not-configured',
                                   true,
                                   :createdAt
                            WHERE NOT EXISTS (SELECT 1 FROM model_configs WHERE id = :id)
                            """)
                    .param("id", DEFAULT_MODEL_PROVIDER_ID)
                    .param("tenantId", DEFAULT_TENANT_ID)
                    .param("createdAt", now)
                    .update();
        };
    }
}
