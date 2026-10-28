package com.cmagent.persistence;

import com.cmagent.core.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 双库在前一个 JVM 退出后由全新 JVM 读取绑定和 Run 身份，不借用进程内缓存。 */
@Testcontainers
class JdbcWorkspaceSelectionProcessTest {
    /** 类独占合成 PostgreSQL，由 Testcontainers 创建及回收。 */
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    /** 类独占合成 MySQL，由 Testcontainers 创建及回收。 */
    @Container static final MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
    @Test void PostgreSQL新进程恢复选择() throws Exception { verify(postgres); }
    @Test void MySQL新进程恢复选择() throws Exception { verify(mysql); }
    private void verify(JdbcDatabaseContainer<?> database) throws Exception {
        var source=new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());
        CmAgentFlyway.configure(source).load().migrate();
        var tenant=UUID.randomUUID();var agent=UUID.randomUUID();var resource=UUID.randomUUID();var workspace=UUID.randomUUID();
        for(String mode:List.of("write","read")) {
            // 连接凭据仅经子进程环境传递，不写命令行、日志、测试产物或断言。
            var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-cp",System.getProperty("java.class.path"),Probe.class.getName(),mode);
            var env=builder.environment();env.put("WORKSPACE_PROBE_URL",database.getJdbcUrl());
            env.put("WORKSPACE_PROBE_USER",database.getUsername());env.put("WORKSPACE_PROBE_PASSWORD",database.getPassword());
            env.put("WORKSPACE_PROBE_TENANT",tenant.toString());env.put("WORKSPACE_PROBE_AGENT",agent.toString());
            env.put("WORKSPACE_PROBE_RESOURCE",resource.toString());env.put("WORKSPACE_PROBE_ID",workspace.toString());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            var process=builder.start();
            try {assertTrue(process.waitFor(30,TimeUnit.SECONDS),"选择元数据新进程验证超时");assertEquals(0,process.exitValue(),"选择元数据新进程验证失败");}
            finally {if(process.isAlive())process.destroyForcibly();}
        }
    }
    /** 只供本测试派生 JVM 使用，失败仅返回退出码，避免 JDBC 实现诊断暴露环境信息。 */
    public static final class Probe {
        public static void main(String[] args) {
            try {
                var source=new DriverManagerDataSource(System.getenv("WORKSPACE_PROBE_URL"),System.getenv("WORKSPACE_PROBE_USER"),System.getenv("WORKSPACE_PROBE_PASSWORD"));
                var rows=new JdbcWorkspaceSelectionRepository(JdbcClient.create(source));
                var tenant=UUID.fromString(System.getenv("WORKSPACE_PROBE_TENANT"));var agent=UUID.fromString(System.getenv("WORKSPACE_PROBE_AGENT"));
                var resource=UUID.fromString(System.getenv("WORKSPACE_PROBE_RESOURCE"));var workspace=UUID.fromString(System.getenv("WORKSPACE_PROBE_ID"));
                if(args[0].equals("write")) {
                    var now=Instant.now();var binding=new WorkspaceBinding(tenant,agent,resource,new WorkspaceSelection(WorkspaceSelection.Mode.SELECTED,workspace),
                            "b".repeat(64),"重启恢复目录","reference",1,"probe",now,now);
                    if(!rows.saveConversation(binding,0))throw new IllegalStateException();rows.saveRun(binding);
                } else {
                    for(var binding:List.of(rows.conversation(tenant,agent,resource).orElseThrow(),rows.run(tenant,agent,resource).orElseThrow()))
                        if(binding.selection().mode()!=WorkspaceSelection.Mode.SELECTED||!workspace.equals(binding.selection().workspaceId())
                                ||binding.version()!=1||!binding.mappingDigest().equals("b".repeat(64)))throw new IllegalStateException();
                    if(rows.conversation(UUID.randomUUID(),agent,resource).isPresent())throw new IllegalStateException();
                }
            } catch(Throwable failure) {System.exit(1);}
        }
    }
}
