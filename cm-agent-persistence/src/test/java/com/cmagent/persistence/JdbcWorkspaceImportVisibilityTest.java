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
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 在Rocky验证SQL owner过滤发生于分页前，且两库的认证主体比较均区分大小写。 */
@Testcontainers
class JdbcWorkspaceImportVisibilityTest {
    /** 类独占PG16容器，由Testcontainers创建及回收。 */
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    /** 类独占MySQL8.4容器，由Testcontainers创建及回收。 */
    @Container static final MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
    @Test void PostgreSQL可见分页与请求核对()throws Exception{verify(postgres);}
    @Test void MySQL可见分页与请求核对()throws Exception{verify(mysql);}
    private void verify(JdbcDatabaseContainer<?> database){
        var data=new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());CmAgentFlyway.configure(data).load().migrate();
        // 旧runs表在MySQL按秒保存，夹具按两库共同精度设置游标，避免把时间舍入误当分页漏洞。
        var jdbc=JdbcClient.create(data);var tenant=UUID.randomUUID();var agent=UUID.randomUUID();var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        jdbc.sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,:id,'合成可见性租户',true,:now)").param("id",tenant.toString()).param("now",Timestamp.from(now)).update();
        var model=UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO model_configs(id,tenant_id,provider_type,display_name,base_url,model_name,encrypted_api_key,enabled,created_at)
                VALUES(:id,:tenant,'OPENAI_COMPATIBLE','合成模型','https://example.invalid','fixture','not-configured',true,:now)
                """).param("id",model.toString()).param("tenant",tenant.toString()).param("now",Timestamp.from(now)).update();
        jdbc.sql("""
                INSERT INTO agent_definitions(id,tenant_id,name,description,system_prompt,model_provider_id,model_name,
                  temperature,max_iterations,enabled,tool_ids_json,created_by,updated_by,created_at,updated_at)
                VALUES(:id,:tenant,'合成Agent','','合成提示',:model,'fixture',0,4,true,'[]','fixture','fixture',:now,:now)
                """).param("id",agent.toString()).param("tenant",tenant.toString()).param("model",model.toString()).param("now",Timestamp.from(now)).update();
        var selections=new JdbcWorkspaceSelectionRepository(jdbc);var conversations=new JdbcConversationRepository(jdbc);var runs=new JdbcRunRepository(jdbc);
        var messageRows=new JdbcConversationMessageRepository(jdbc,new com.fasterxml.jackson.databind.ObjectMapper(),new TransactionTemplate(new DataSourceTransactionManager(data)));
        var privateConversation=new Conversation(UUID.randomUUID(),tenant,agent,"PRIVATE_TITLE","Owner",now,now);
        var sharedNew=new Conversation(UUID.randomUUID(),tenant,agent,"共享甲","someone",now.minusSeconds(10),now.minusSeconds(10));
        var sharedOld=new Conversation(UUID.randomUUID(),tenant,agent,"共享乙","someone",now.minusSeconds(20),now.minusSeconds(20));
        for(var c:List.of(privateConversation,sharedNew,sharedOld))conversations.save(tenant,c);
        var privateRun=run(tenant,agent,"Owner",now,"PRIVATE_INPUT");var newer=run(tenant,agent,"someone",now.minusSeconds(10),"共享甲");var older=run(tenant,agent,"someone",now.minusSeconds(20),"共享乙");
        for(var r:List.of(privateRun,newer,older))runs.save(tenant,r);
        selections.saveConversation(binding(tenant,agent,privateConversation.id(),now),0);selections.saveRun(binding(tenant,agent,privateRun.id(),now));
        messageRows.append(tenant,new ConversationMessageDraft(UUID.randomUUID(),tenant,privateConversation.id(),MessageRole.USER,"合成",List.of(MessageContentBlock.text("PRIVATE_MESSAGE")),privateRun.id(),now));
        // 小写owner在MySQL默认ci排序下也不能命中大写Owner的私有来源；隐藏首条不能产生空页。
        var page=conversations.listVisible(tenant,agent,"owner",new ConversationPageRequest(1,null,null));assertThat(page).containsExactly(sharedNew);
        assertThat(conversations.listVisible(tenant,agent,"owner",new ConversationPageRequest(1,sharedNew.updatedAt(),sharedNew.id()))).containsExactly(sharedOld);
        assertThat(conversations.listVisible(tenant,agent,"Owner",new ConversationPageRequest(1,null,null))).extracting(Conversation::id).containsExactly(privateConversation.id());
        assertThat(runs.listVisible(tenant,agent,"owner",new RunPageRequest(1,null,null))).containsExactly(newer);
        assertThat(runs.listVisible(tenant,agent,"owner",new RunPageRequest(1,newer.startedAt(),newer.id()))).containsExactly(older);
        assertThat(runs.listVisible(tenant,agent,"Owner",new RunPageRequest(1,null,null))).containsExactly(privateRun);
        assertThat(conversations.listVisible(UUID.randomUUID(),agent,"Owner",new ConversationPageRequest(1,null,null))).isEmpty();
        assertThat(runs.listVisible(tenant,UUID.randomUUID(),"Owner",new RunPageRequest(1,null,null))).isEmpty();
        var imports=new JdbcWorkspaceImportRepository(jdbc,new TransactionTemplate(new DataSourceTransactionManager(data)));UUID request=UUID.randomUUID();
        for(String owner:List.of("Owner","owner","owner ")){
            var scope=new WorkspaceImportScope(tenant,owner,agent);var base=WorkspaceImport.draft(UUID.randomUUID(),scope,"合成请求",List.of(new WorkspaceImportFile(UUID.randomUUID(),"a.txt",1,"a".repeat(64))),now,now.plusSeconds(60));
            var batch=new WorkspaceImport(base.id(),scope,base.displayName(),base.files(),base.manifestDigest(),base.state(),base.uploaded(),1,now,base.expiresAt(),false,request);
            assertThat(imports.reserve(batch,WorkspaceImportLimits.defaults(),now)).isTrue();
            assertThat(imports.findByRequest(scope,request)).contains(batch);
            var other=WorkspaceImport.draft(UUID.randomUUID(),scope,"不可重复",base.files(),now,now.plusSeconds(60));
            var duplicate=new WorkspaceImport(other.id(),scope,other.displayName(),other.files(),other.manifestDigest(),other.state(),other.uploaded(),1,now,other.expiresAt(),false,request);
            assertThat(imports.reserve(duplicate,WorkspaceImportLimits.defaults(),now)).isFalse();
        }
        assertThat(imports.findByRequest(new WorkspaceImportScope(tenant,"OWNER",agent),request)).isEmpty();
        var restarted=new JdbcWorkspaceImportRepository(JdbcClient.create(data),new TransactionTemplate(new DataSourceTransactionManager(data)));
        assertThat(restarted.findByRequest(new WorkspaceImportScope(tenant,"Owner",agent),request)).isPresent();
    }
    private static RunRecord run(UUID tenant,UUID agent,String owner,Instant now,String text){return new RunRecord(UUID.randomUUID(),tenant,agent,owner,RunKind.NORMAL,RunStatus.SUCCEEDED,text,text,"",now,now.plusSeconds(1));}
    private static WorkspaceBinding binding(UUID tenant,UUID agent,UUID id,Instant now){return new WorkspaceBinding(tenant,agent,id,new WorkspaceSelection(WorkspaceSelection.Mode.IMPORTED,UUID.randomUUID()),"a".repeat(64),"合成私有快照","",1,"Owner",now,now);}
}
