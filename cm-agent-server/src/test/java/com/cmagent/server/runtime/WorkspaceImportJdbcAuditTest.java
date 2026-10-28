package com.cmagent.server.runtime;

import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.*;
import com.cmagent.core.security.DefaultPermissionEvaluator;
import com.cmagent.persistence.*;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.WorkspaceContextProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
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
import static org.mockito.Mockito.*;

/**
 * Rocky双库真实事务验证：外层持租户锁→导入/owner拒绝→业务回滚→最终边界写拒绝审计。
 * 只测试元数据，存储为不会读写的mock，不调用模型、不创建用户资料文件。
 */
@Testcontainers
class WorkspaceImportJdbcAuditTest {
    /** 合成PG16由Testcontainers独占创建/关闭。 */
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    /** 合成MySQL8.4由Testcontainers独占创建/关闭。 */
    @Container static final MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
    @Test void PostgreSQL回滚后拒绝审计保留(){verify(postgres);}
    @Test void MySQL回滚后拒绝审计保留(){verify(mysql);}
    private void verify(JdbcDatabaseContainer<?> database){
        var data=new DriverManagerDataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());CmAgentFlyway.configure(data).load().migrate();
        var jdbc=JdbcClient.create(data);var tx=new TransactionTemplate(new DataSourceTransactionManager(data));
        var tenant=UUID.randomUUID();var agent=UUID.randomUUID();var now=Instant.now();
        jdbc.sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,:id,'合成审计租户',true,:now)").param("id",tenant.toString()).param("now",Timestamp.from(now)).update();
        var rows=new JdbcWorkspaceImportRepository(jdbc,tx);var scope=new WorkspaceImportScope(tenant,"audit-owner",agent);
        var draft=WorkspaceImport.draft(UUID.randomUUID(),scope,"合成草稿",List.of(new WorkspaceImportFile(UUID.randomUUID(),"a.txt",1,"a".repeat(64))),now,now.plusSeconds(3600));
        assertThat(rows.reserve(draft,WorkspaceImportLimits.defaults(),now)).isTrue();
        var policy=new WorkspaceContextProperties();policy.setEnabled(true);policy.setRootDirectory("/fixture-reference");policy.getImports().setEnabled(true);policy.getImports().setRootDirectory("/fixture-imports");
        byte[] randomKey=new byte[32];new java.security.SecureRandom().nextBytes(randomKey);policy.getImports().setEncryptionKey(Base64.getEncoder().encodeToString(randomKey));policy.validate();
        var agentRows=mock(AgentDefinitionRepository.class);when(agentRows.findByTenantAndId(tenant,agent)).thenReturn(Optional.of(new AgentDefinition(agent,tenant,"合成Agent","","合成提示",UUID.randomUUID(),"fake",0,4,true,List.of(),scope.principalId(),scope.principalId())));
        var audit=new AuditAppender(new JdbcAuditEventRepository(jdbc,tx));var beans=new StaticListableBeanFactory();beans.addBean("objects",mock(WorkspaceImportStorage.class));beans.addBean("transactions",tx);
        var reader=new WorkspaceImportBodyReader();var principal=new PrincipalRef(tenant,scope.principalId(),"合成主体",Set.of("agent:read","agent:run"));
        try(var validation=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            var service=new WorkspaceImportService(policy,rows,agentRows,beans.getBeanProvider(WorkspaceImportStorage.class),new DefaultPermissionEvaluator(),audit,beans.getBeanProvider(TransactionTemplate.class),reader,new ObjectMapper(),validation.getValidator());
            var deferred=catchThrowableOfType(()->tx.execute(status->{service.requireReady(principal,agent,draft.id());return null;}),DeferredWorkspaceFailure.class);
            assertThat(deferred).isNotNull();assertThat(count(jdbc,tenant)).isZero();
            var failure=deferred.finish();assertThat(failure.code()).isEqualTo(ApiErrorCode.WORKSPACE_IMPORT_CONFLICT);assertThat(count(jdbc,tenant)).isEqualTo(1);
            assertThat(jdbc.sql("SELECT message FROM audit_events WHERE tenant_id=:tenant").param("tenant",tenant.toString()).query(String.class).single()).contains(failure.errorId());
            deferred.finish();assertThat(count(jdbc,tenant)).isEqualTo(1);
            var selections=new JdbcWorkspaceSelectionRepository(jdbc);var conversation=new Conversation(UUID.randomUUID(),tenant,agent,"合成私有",scope.principalId(),now,now);
            selections.saveConversation(new WorkspaceBinding(tenant,agent,conversation.id(),new WorkspaceSelection(WorkspaceSelection.Mode.IMPORTED,draft.id()),draft.manifestDigest(),"合成导入","",1,scope.principalId(),now,now),0);
            var access=new WorkspaceImportAccess(selections,mock(ConversationRepository.class),mock(RunRepository.class),audit);
            var other=new PrincipalRef(tenant,"another-owner","合成",Set.of("agent:read","agent:run"));
            var denied=catchThrowableOfType(()->tx.execute(status->{access.conversation(other,conversation);return null;}),DeferredWorkspaceFailure.class);
            assertThat(count(jdbc,tenant)).isEqualTo(1);assertThat(denied.finish().code()).isEqualTo(ApiErrorCode.WORKSPACE_RESOURCE_NOT_FOUND);assertThat(count(jdbc,tenant)).isEqualTo(2);
        }finally{reader.close();}
    }
    private static long count(JdbcClient jdbc,UUID tenant){return jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE tenant_id=:tenant").param("tenant",tenant.toString()).query(Long.class).single();}
}
