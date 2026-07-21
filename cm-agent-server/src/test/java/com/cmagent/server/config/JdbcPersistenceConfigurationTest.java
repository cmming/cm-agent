package com.cmagent.server.config;

import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.core.repository.ToolCallRepository;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.persistence.JdbcAuditEventRepository;
import com.cmagent.persistence.JdbcRunRepository;
import com.cmagent.persistence.JdbcModelConfigRepository;
import com.cmagent.persistence.JdbcToolCallRepository;
import com.cmagent.persistence.JdbcHttpToolConfigRepository;
import com.cmagent.persistence.JdbcMcpToolPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cmagent.server.store.InMemoryPlatformStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class JdbcPersistenceConfigurationTest {

    @Test
    void jdbcModeDoesNotRegisterMemoryStore() {
        new ApplicationContextRunner()
                .withUserConfiguration(ServerRepositoryConfiguration.class)
                .withPropertyValues(
                        "cm-agent.persistence.mode=jdbc",
                        "cm-agent.persistence.jdbc.url=jdbc:postgresql://localhost/cm_agent"
                )
                .run(context -> assertThat(context).doesNotHaveBean(InMemoryPlatformStore.class));
    }

    @Test
    void jdbcConfigurationProvidesRuntimeRepositories() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        JdbcPersistenceConfiguration configuration = new JdbcPersistenceConfiguration();

        assertThat(configuration.jdbcAuditEventRepository(jdbcClient))
                .isInstanceOf(JdbcAuditEventRepository.class)
                .isInstanceOf(AuditEventRepository.class);
        assertThat(configuration.jdbcRunRepository(jdbcClient))
                .isInstanceOf(JdbcRunRepository.class)
                .isInstanceOf(RunRepository.class);
        assertThat(configuration.jdbcModelConfigRepository(jdbcClient))
                .isInstanceOf(JdbcModelConfigRepository.class)
                .isInstanceOf(ModelConfigRepository.class);
        assertThat(configuration.jdbcToolCallRepository(jdbcClient, transactionTemplate))
                .isInstanceOf(JdbcToolCallRepository.class)
                .isInstanceOf(ToolCallRepository.class);
        assertThat(configuration.jdbcHttpToolConfigRepository(jdbcClient, new ObjectMapper(), transactionTemplate))
                .isInstanceOf(JdbcHttpToolConfigRepository.class)
                .isInstanceOf(HttpToolConfigRepository.class);
        assertThat(configuration.jdbcMcpToolPublicationRepository(jdbcClient, transactionTemplate)
                .isInstanceOf(JdbcMcpToolPublicationRepository.class)
                .isInstanceOf(McpToolPublicationRepository.class);
    }

    @Test
    void defaultTenantDataInitializerBindsCreatedAtAsSqlTimestamp() throws Exception {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        JdbcClient.StatementSpec tenantInsert = statementSpec();
        JdbcClient.StatementSpec modelConfigInsert = statementSpec();
        when(jdbcClient.sql(contains("INSERT INTO tenants"))).thenReturn(tenantInsert);
        when(jdbcClient.sql(contains("INSERT INTO model_configs"))).thenReturn(modelConfigInsert);

        ApplicationRunner initializer = new JdbcPersistenceConfiguration()
                .defaultTenantDataInitializer(jdbcClient, mock(Flyway.class));

        initializer.run(null);

        verify(tenantInsert).param(eq("createdAt"), any(Timestamp.class));
        verify(modelConfigInsert).param(eq("createdAt"), any(Timestamp.class));
    }

    private static JdbcClient.StatementSpec statementSpec() {
        JdbcClient.StatementSpec statementSpec = mock(JdbcClient.StatementSpec.class);
        when(statementSpec.param(anyString(), any())).thenReturn(statementSpec);
        when(statementSpec.update()).thenReturn(1);
        return statementSpec;
    }
}
