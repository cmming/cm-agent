package com.cmagent.server.runtime;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;

/** 仅ssh rocky的Maven21容器重跑同一工作区合同；独占数据库，不连接部署者资料或数据库。 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "CM_AGENT_TEST_WORKSPACE_JDBC", matches = "true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HarnessWorkspaceContextJdbcFlowTest extends HarnessWorkspaceContextFlowTest {
    /** 临时库由Testcontainers创建和关闭，连接信息不输出或落盘。 */
    @org.testcontainers.junit.jupiter.Container static final JdbcDatabaseContainer<?> database = "mysql".equals(System.getProperty("cm-agent.test.database"))
            ? new MySQLContainer<>("mysql:8.4") : new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("cm-agent.persistence.mode", () -> "jdbc");
        properties.add("cm-agent.persistence.jdbc.url", database::getJdbcUrl);
        properties.add("cm-agent.persistence.jdbc.username", database::getUsername);
        properties.add("cm-agent.persistence.jdbc.password", database::getPassword);
        properties.add("cm-agent.persistence.jdbc.driver-class-name", database::getDriverClassName);
    }
}
