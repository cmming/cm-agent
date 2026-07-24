package com.cmagent.server.config;

import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolCallRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.persistence.JdbcAuditEventRepository;
import com.cmagent.persistence.JdbcAgentDefinitionRepository;
import com.cmagent.persistence.JdbcRunRepository;
import com.cmagent.persistence.JdbcModelConfigRepository;
import com.cmagent.persistence.JdbcToolDefinitionRepository;
import com.cmagent.persistence.JdbcToolCallRepository;
import com.cmagent.persistence.JdbcToolGrantRepository;
import com.cmagent.persistence.JdbcHttpToolConfigRepository;
import com.cmagent.persistence.JdbcMcpToolPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;

@Configuration
@ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "jdbc")
/** JDBC 持久化条件配置，负责装配事务、Flyway 和 JDBC 访问所需组件。 */
public class JdbcPersistenceConfiguration {
    private static final String DEFAULT_TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String DEFAULT_MODEL_PROVIDER_ID = "00000000-0000-0000-0000-000000000301";

    /**
     * 创建 JDBC 连接池，并从外部配置读取连接信息。
     *
     * @param properties 持久化和 JDBC 连接配置
     * @return CM Agent 数据源
     */
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

    /**
     * 创建并执行 Flyway 数据库迁移。
     *
     * @param cmAgentDataSource CM Agent 数据源
     * @return 已完成迁移的 Flyway 组件
     * @throws RuntimeException 迁移执行失败时抛出
     */
    @Bean
    Flyway cmAgentFlyway(DataSource cmAgentDataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(cmAgentDataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        return flyway;
    }

    /**
     * 创建基于命名参数的 JDBC 客户端。
     *
     * @param cmAgentDataSource CM Agent 数据源
     * @param cmAgentFlyway     已完成迁移的 Flyway 组件，用于保证初始化顺序
     * @return JDBC 客户端
     */
    @Bean
    JdbcClient cmAgentJdbcClient(DataSource cmAgentDataSource, Flyway cmAgentFlyway) {
        return JdbcClient.create(cmAgentDataSource);
    }

    /**
     * 创建 JDBC 事务管理器。
     *
     * @param cmAgentDataSource CM Agent 数据源
     * @return 数据源事务管理器
     */
    @Bean
    PlatformTransactionManager cmAgentTransactionManager(DataSource cmAgentDataSource) {
        return new DataSourceTransactionManager(cmAgentDataSource);
    }

    /**
     * 创建声明式事务模板，供服务和 Repository 复用。
     *
     * @param cmAgentTransactionManager JDBC 事务管理器
     * @return Spring 事务模板
     */
    @Bean
    TransactionTemplate cmAgentTransactionTemplate(PlatformTransactionManager cmAgentTransactionManager) {
        return new TransactionTemplate(cmAgentTransactionManager);
    }

    /**
     * @param cmAgentJdbcClient JDBC 客户端 @return Agent 定义 Repository
     */
    @Bean
    AgentDefinitionRepository jdbcAgentDefinitionRepository(JdbcClient cmAgentJdbcClient) {
        return new JdbcAgentDefinitionRepository(cmAgentJdbcClient, new ObjectMapper());
    }

    /**
     * @param cmAgentJdbcClient JDBC 客户端 @return 工具定义 Repository
     */
    @Bean
    ToolDefinitionRepository jdbcToolDefinitionRepository(JdbcClient cmAgentJdbcClient) {
        return new JdbcToolDefinitionRepository(cmAgentJdbcClient);
    }

    /**
     * @param cmAgentJdbcClient JDBC 客户端 @return 工具授权 Repository
     */
    @Bean
    ToolGrantRepository jdbcToolGrantRepository(JdbcClient cmAgentJdbcClient) {
        return new JdbcToolGrantRepository(cmAgentJdbcClient);
    }

    /**
     * 创建带事务支持的审计 Repository。
     *
     * @param cmAgentJdbcClient          JDBC 客户端
     * @param cmAgentTransactionTemplate 事务模板
     * @return 审计事件 Repository
     */
    @Bean
    AuditEventRepository jdbcAuditEventRepository(
            JdbcClient cmAgentJdbcClient,
            TransactionTemplate cmAgentTransactionTemplate
    ) {
        return new JdbcAuditEventRepository(cmAgentJdbcClient, cmAgentTransactionTemplate);
    }

    /**
     * @param cmAgentJdbcClient JDBC 客户端 @return 运行记录 Repository
     */
    @Bean
    RunRepository jdbcRunRepository(JdbcClient cmAgentJdbcClient) {
        return new JdbcRunRepository(cmAgentJdbcClient);
    }

    /**
     * @param cmAgentJdbcClient JDBC 客户端 @return 模型配置 Repository
     */
    @Bean
    ModelConfigRepository jdbcModelConfigRepository(JdbcClient cmAgentJdbcClient) {
        return new JdbcModelConfigRepository(cmAgentJdbcClient);
    }

    /**
     * 创建带事务支持的工具调用 Repository。
     *
     * @param cmAgentJdbcClient          JDBC 客户端
     * @param cmAgentTransactionTemplate 事务模板
     * @return 工具调用 Repository
     */
    @Bean
    ToolCallRepository jdbcToolCallRepository(
            JdbcClient cmAgentJdbcClient,
            TransactionTemplate cmAgentTransactionTemplate
    ) {
        return new JdbcToolCallRepository(cmAgentJdbcClient, cmAgentTransactionTemplate);
    }

    /**
     * 创建带 JSON 映射和事务支持的 HTTP 工具配置 Repository。
     *
     * @param cmAgentJdbcClient          JDBC 客户端
     * @param objectMapper               JSON 映射器
     * @param cmAgentTransactionTemplate 事务模板
     * @return HTTP 工具配置 Repository
     */
    @Bean
    HttpToolConfigRepository jdbcHttpToolConfigRepository(
            JdbcClient cmAgentJdbcClient,
            ObjectMapper objectMapper,
            TransactionTemplate cmAgentTransactionTemplate
    ) {
        return new JdbcHttpToolConfigRepository(cmAgentJdbcClient, objectMapper, cmAgentTransactionTemplate);
    }

    /**
     * 创建带行锁和事务支持的 MCP 发布 Repository。
     *
     * @param cmAgentJdbcClient          JDBC 客户端
     * @param cmAgentTransactionTemplate 事务模板
     * @return MCP 工具发布 Repository
     */
    @Bean
    McpToolPublicationRepository jdbcMcpToolPublicationRepository(
            JdbcClient cmAgentJdbcClient,
            TransactionTemplate cmAgentTransactionTemplate
    ) {
        return new JdbcMcpToolPublicationRepository(cmAgentJdbcClient, cmAgentTransactionTemplate);
    }

    /**
     * 创建 JDBC 默认租户和默认模型数据初始化器。
     *
     * @param cmAgentJdbcClient JDBC 客户端
     * @param cmAgentFlyway     Flyway 组件，用于保证迁移先于初始化执行
     * @return Spring 应用启动回调
     */
    @Bean
    ApplicationRunner defaultTenantDataInitializer(JdbcClient cmAgentJdbcClient, Flyway cmAgentFlyway) {
        return args -> {
            Timestamp now = Timestamp.from(Instant.now());
            cmAgentJdbcClient.sql("""
                            /**
                             * tenants：处理该类内部的业务逻辑或辅助计算。
                             */
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
                            /**
                             * model_configs：处理该类内部的业务逻辑或辅助计算。
                             */
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
