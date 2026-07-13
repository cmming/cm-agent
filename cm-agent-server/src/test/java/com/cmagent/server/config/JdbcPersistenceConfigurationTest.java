package com.cmagent.server.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcPersistenceConfigurationTest {

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
