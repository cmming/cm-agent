package com.cmagent.persistence;
import com.cmagent.core.domain.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
/** 真实双库验证跨实例配额串行化、发布事务回滚和租约防误释放。 */
@Testcontainers
class JdbcSkillArtifactRepositoryTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
    @Test void PostgreSQL产物契约()throws Exception{verify(postgres);}
    @Test void MySQL产物契约()throws Exception{verify(mysql);}
    private void verify(JdbcDatabaseContainer<?> db)throws Exception{
        var ds=new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());CmAgentFlyway.configure(ds).load().migrate();
        var jdbc=JdbcClient.create(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var first=new JdbcSkillArtifactRepository(jdbc,tx);var second=new JdbcSkillArtifactRepository(jdbc,tx);
        UUID tenant=UUID.randomUUID(),run=UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,:code,'文件配额测试',true,:now)").param("id",tenant.toString()).param("code",tenant.toString()).param("now",java.sql.Timestamp.from(Instant.now())).update();
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var tasks=new ArrayList<Future<Boolean>>();
            for(int i=0;i<12;i++){var repo=i%2==0?first:second;var a=artifact(tenant,run);tasks.add(workers.submit(()->repo.reserve(a,5,5,8,8)));}
            int successes=0;for(var task:tasks)if(task.get())successes++;assertThat(successes).isEqualTo(5);
        }
        var a=first.list(tenant,run).getFirst();assertThat(first.find(UUID.randomUUID(),a.id())).isEmpty();
        assertThat(first.stored(tenant,a.id(),"a".repeat(64))).isTrue();
        tx.executeWithoutResult(status->{first.publish(tenant,run,Instant.now().plusSeconds(60));status.setRollbackOnly();});
        assertThat(second.find(tenant,a.id()).orElseThrow().status()).isEqualTo(SkillArtifactStatus.PENDING);
        first.publish(tenant,run,Instant.now().plusSeconds(60));
        Instant now=Instant.now();UUID token=UUID.randomUUID();assertThat(first.lease(tenant,a.id(),token,now,now.plusSeconds(30),false)).isTrue();
        assertThat(second.lease(tenant,a.id(),UUID.randomUUID(),now,now.plusSeconds(30),false)).isFalse();
        first.release(tenant,a.id(),UUID.randomUUID(),true);assertThat(first.find(tenant,a.id()).orElseThrow().leaseToken()).isEqualTo(token);
        first.discard(tenant,run);assertThat(second.lease(tenant,a.id(),UUID.randomUUID(),now,now.plusSeconds(30),true)).isFalse();
        first.release(tenant,a.id(),token,false);UUID deletion=UUID.randomUUID();assertThat(second.lease(tenant,a.id(),deletion,now,now.plusSeconds(30),true)).isTrue();
        second.release(tenant,a.id(),deletion,true);assertThat(first.find(tenant,a.id()).orElseThrow().status()).isEqualTo(SkillArtifactStatus.DELETED);
        assertThat(first.reserve(artifact(tenant,run),5,5,8,8)).isFalse();
        assertThat(first.reserve(artifact(tenant,UUID.randomUUID()),5,5,8,8)).isTrue();
    }
    private SkillArtifact artifact(UUID tenant,UUID run){return new SkillArtifact(UUID.randomUUID(),tenant,"fixture",UUID.randomUUID(),run,RunKind.NORMAL,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID().toString(),"测试.txt","text/plain",1,"",SkillArtifactStatus.STAGING,Instant.now(),Instant.now().plusSeconds(60),null,null);}
}
