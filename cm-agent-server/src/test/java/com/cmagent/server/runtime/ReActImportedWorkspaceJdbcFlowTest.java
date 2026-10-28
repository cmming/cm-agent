package com.cmagent.server.runtime;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** 仅Rocky容器验证ReAct导入/冻结/审批/停止，不能以memory通过替代真实JDBC合同。 */
@Testcontainers
@EnabledIfEnvironmentVariable(named="CM_AGENT_TEST_WORKSPACE_JDBC",matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ReActImportedWorkspaceJdbcFlowTest extends ReActImportedWorkspaceFlowTest{
    /** 类独占临时库；父合同使用同一实际仓储、状态和密文，不连接部署数据库。 */
    @Container static final JdbcDatabaseContainer<?> database="mysql".equals(System.getProperty("cm-agent.test.database"))
            ?new MySQLContainer<>("mysql:8.4"):new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry p){
        p.add("cm-agent.persistence.mode",()->"jdbc");p.add("cm-agent.persistence.jdbc.url",database::getJdbcUrl);
        p.add("cm-agent.persistence.jdbc.username",database::getUsername);p.add("cm-agent.persistence.jdbc.password",database::getPassword);
        p.add("cm-agent.persistence.jdbc.driver-class-name",database::getDriverClassName);
    }
}
