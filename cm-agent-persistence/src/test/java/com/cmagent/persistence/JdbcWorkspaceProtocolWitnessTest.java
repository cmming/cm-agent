package com.cmagent.persistence;

import com.cmagent.core.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.Timestamp;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** 仅Rocky双库容器：来源见证在元数据丢失/状态变化/新JVM后仍保护私有标题与输入。 */
@Testcontainers
class JdbcWorkspaceProtocolWitnessTest {
    /** 由Testcontainers创建及回收的合成PG16。 */
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    /** 由Testcontainers创建及回收的合成MySQL8.4。 */
    @Container static final MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
    @Test void PostgreSQL见证阻止缺失元数据回退且新JVM恢复()throws Exception{verify(postgres);}
    @Test void MySQL见证阻止缺失元数据回退且新JVM恢复()throws Exception{verify(mysql);}
    private void verify(JdbcDatabaseContainer<?> database)throws Exception{
        var data=new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());CmAgentFlyway.configure(data).load().migrate();
        var jdbc=JdbcClient.create(data);var tx=new TransactionTemplate(new DataSourceTransactionManager(data));
        var tenant=UUID.randomUUID();var agent=UUID.randomUUID();var model=UUID.randomUUID();var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        jdbc.sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,:id,'合成见证租户',true,:now)").param("id",tenant.toString()).param("now",Timestamp.from(now)).update();
        jdbc.sql("""
                INSERT INTO model_configs(id,tenant_id,provider_type,display_name,base_url,model_name,encrypted_api_key,enabled,created_at)
                VALUES(:id,:tenant,'OPENAI_COMPATIBLE','合成模型','https://example.invalid','fixture','not-configured',true,:now)
                """).param("id",model.toString()).param("tenant",tenant.toString()).param("now",Timestamp.from(now)).update();
        jdbc.sql("""
                INSERT INTO agent_definitions(id,tenant_id,name,description,system_prompt,model_provider_id,model_name,temperature,max_iterations,enabled,tool_ids_json,created_by,updated_by,created_at,updated_at)
                VALUES(:id,:tenant,'合成见证Agent','','合成提示',:model,'fixture',0,4,true,'[]','Owner','Owner',:now,:now)
                """).param("id",agent.toString()).param("tenant",tenant.toString()).param("model",model.toString()).param("now",Timestamp.from(now)).update();
        var runs=new JdbcRunRepository(jdbc);var conversations=new JdbcConversationRepository(jdbc);
        var privateConversation=new Conversation(UUID.randomUUID(),tenant,agent,"PRIVATE_TITLE","Owner",now,now);
        conversations.saveWithWorkspaceProtocol(tenant,privateConversation,3);
        var privateRun=RunRecord.create(UUID.randomUUID(),tenant,agent,"Owner","PRIVATE_INPUT",now);
        runs.saveWithWorkspaceProtocol(tenant,privateRun,3);
        new JdbcConversationMessageRepository(jdbc,new com.fasterxml.jackson.databind.ObjectMapper(),tx).append(tenant,new ConversationMessageDraft(UUID.randomUUID(),tenant,privateConversation.id(),MessageRole.USER,"合成",List.of(MessageContentBlock.text("PRIVATE_MESSAGE")),privateRun.id(),now));
        // 从未插入选择行，相当于首发补元数据前窗口及长期选择全部丢失；不能靠选择行决定私有性。
        assertThat(runs.workspaceProtocol(tenant,agent,privateRun.id())).isEqualTo(3);
        assertThat(conversations.workspaceProtocol(tenant,agent,privateConversation.id())).isEqualTo(3);
        assertThat(runs.listVisible(tenant,agent,"owner",new RunPageRequest(10,null,null))).isEmpty();
        assertThat(conversations.listVisible(tenant,agent,"owner",new ConversationPageRequest(10,null,null))).isEmpty();
        assertThat(runs.visibleConversationFromWorkspace(privateConversation,"owner")).isFalse();
        assertThat(runs.listVisible(tenant,agent,"Owner",new RunPageRequest(10,null,null))).extracting(RunRecord::id).containsExactly(privateRun.id());
        var waiting=runs.waitForApproval(tenant,privateRun.id());assertThat(runs.resumeWaitingApproval(waiting)).isTrue();
        var resumed=runs.findByTenantAndAgentAndId(tenant,agent,privateRun.id()).orElseThrow();assertThat(runs.requestCancellation(resumed)).isTrue();
        runs.complete(tenant,privateRun.id(),RunStatus.CANCELLED,"","合成停止",now.plusSeconds(1));assertThat(runs.workspaceProtocol(tenant,agent,privateRun.id())).isEqualTo(3);
        assertThatThrownBy(()->runs.save(tenant,privateRun)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(runs.workspaceProtocol(tenant,agent,privateRun.id())).isEqualTo(3);
        assertThatThrownBy(()->runs.workspaceProtocol(UUID.randomUUID(),agent,privateRun.id())).isInstanceOf(NoSuchElementException.class);
        var empty=new Conversation(UUID.randomUUID(),tenant,agent,"空会话","Owner",now,now);conversations.saveWithWorkspaceProtocol(tenant,empty,3);
        conversations.updateWorkspaceProtocol(tenant,agent,empty.id(),0);assertThat(conversations.workspaceProtocol(tenant,agent,empty.id())).isZero();
        assertThat(conversations.findByTenantAndAgentAndId(tenant,agent,empty.id())).contains(empty);
        probe(database,tenant,agent,privateConversation.id(),privateRun.id());
    }
    private static void probe(JdbcDatabaseContainer<?> database,UUID tenant,UUID agent,UUID conversation,UUID run)throws Exception{
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),Probe.class.getName());
        var env=builder.environment();env.put("PROTOCOL_PROBE_URL",database.getJdbcUrl());env.put("PROTOCOL_PROBE_USER",database.getUsername());env.put("PROTOCOL_PROBE_PASSWORD",database.getPassword());
        env.put("PROTOCOL_PROBE_TENANT",tenant.toString());env.put("PROTOCOL_PROBE_AGENT",agent.toString());env.put("PROTOCOL_PROBE_CONVERSATION",conversation.toString());env.put("PROTOCOL_PROBE_RUN",run.toString());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD);var process=builder.start();
        try{assertThat(process.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();}finally{if(process.isAlive())process.destroyForcibly();}
    }
    /** 子进程只从环境取得合成连接，失败不输出内部URL或凭据。 */
    public static final class Probe {
        /** @param args 无参数，避免连接凭据进入命令行 */
        public static void main(String[] args){try{
            var source=new DriverManagerDataSource(System.getenv("PROTOCOL_PROBE_URL"),System.getenv("PROTOCOL_PROBE_USER"),System.getenv("PROTOCOL_PROBE_PASSWORD"));var jdbc=JdbcClient.create(source);
            var tenant=UUID.fromString(System.getenv("PROTOCOL_PROBE_TENANT"));var agent=UUID.fromString(System.getenv("PROTOCOL_PROBE_AGENT"));var run=UUID.fromString(System.getenv("PROTOCOL_PROBE_RUN"));var conversation=UUID.fromString(System.getenv("PROTOCOL_PROBE_CONVERSATION"));
            var runs=new JdbcRunRepository(jdbc);var conversations=new JdbcConversationRepository(jdbc);
            if(runs.workspaceProtocol(tenant,agent,run)!=3 || conversations.workspaceProtocol(tenant,agent,conversation)!=3
                    || !conversations.listVisible(tenant,agent,"other",new ConversationPageRequest(10,null,null)).stream().noneMatch(c->c.id().equals(conversation)))throw new IllegalStateException();
        }catch(Exception failure){System.exit(1);}}
    }
}
