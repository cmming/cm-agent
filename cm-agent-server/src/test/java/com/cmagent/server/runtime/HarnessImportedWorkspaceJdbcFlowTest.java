package com.cmagent.server.runtime;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** 仅ssh rocky的指定Maven21容器执行同一导入合同，PG16/MySQL8.4按显式参数选择。 */
@Testcontainers
@EnabledIfEnvironmentVariable(named="CM_AGENT_TEST_WORKSPACE_JDBC",matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class HarnessImportedWorkspaceJdbcFlowTest extends HarnessImportedWorkspaceFlowTest{
    /** 临时数据库由Testcontainers创建/关闭，连接凭据不写日志或命令行。 */
    @Container static final JdbcDatabaseContainer<?> database="mysql".equals(System.getProperty("cm-agent.test.database"))
            ?new MySQLContainer<>("mysql:8.4"):new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry p){
        p.add("cm-agent.persistence.mode",()->"jdbc");p.add("cm-agent.persistence.jdbc.url",database::getJdbcUrl);
        p.add("cm-agent.persistence.jdbc.username",database::getUsername);p.add("cm-agent.persistence.jdbc.password",database::getPassword);
        p.add("cm-agent.persistence.jdbc.driver-class-name",database::getDriverClassName);
    }
}
