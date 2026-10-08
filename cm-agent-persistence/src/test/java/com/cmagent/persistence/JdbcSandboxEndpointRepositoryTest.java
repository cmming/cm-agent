package com.cmagent.persistence;

import com.cmagent.core.domain.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 两种目标数据库真实验证租户过滤、CAS、租户锁串行化及事务回滚。 */
@Testcontainers
class JdbcSandboxEndpointRepositoryTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
    @Test void PostgreSQL端点隔离与事务契约()throws Exception{verify(postgres);}
    @Test void MySQL端点隔离与事务契约()throws Exception{verify(mysql);}
    private void verify(JdbcDatabaseContainer<?> container)throws Exception{
        var source=new DriverManagerDataSource(container.getJdbcUrl(),container.getUsername(),container.getPassword());
        CmAgentFlyway.configure(source).load().migrate();
        var jdbc=JdbcClient.create(source);
        var repository=new JdbcSandboxEndpointRepository(jdbc,new TransactionTemplate(new DataSourceTransactionManager(source)));
        UUID tenant=UUID.randomUUID(),other=UUID.randomUUID();
        for(UUID id:List.of(tenant,other))jdbc.sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,:code,:name,true,:at)")
            .param("id",id.toString()).param("code",id.toString()).param("name","沙箱隔离测试").param("at",java.sql.Timestamp.from(Instant.now())).update();
        var first=endpoint(tenant,UUID.randomUUID(),1);
        var second=endpoint(tenant,UUID.randomUUID(),1);
        repository.atomic(tenant,()->{repository.insert(first);repository.insert(second);return null;});
        assertThat(repository.find(tenant,first.id()).orElseThrow().sshAuthType()).isEqualTo(SandboxSshAuthType.KEY);
        var passwordEndpoint=new SandboxEndpoint(UUID.randomUUID(),tenant,"密码端点","docker",SandboxConnectionMode.SSH,
            "example.invalid",22,"fixture",true,"测试密文占位",1,1,0,null,"NOT_TESTED",false,Instant.now(),Instant.now(),SandboxSshAuthType.PASSWORD);
        repository.insert(passwordEndpoint);
        assertThat(repository.find(tenant,passwordEndpoint.id()).orElseThrow().sshAuthType()).isEqualTo(SandboxSshAuthType.PASSWORD);
        assertThat(repository.find(other,passwordEndpoint.id())).isEmpty();
        var keyEndpoint=new SandboxEndpoint(passwordEndpoint.id(),tenant,"轮换端点","docker",SandboxConnectionMode.SSH,
            "example.invalid",22,"fixture",true,"更新密文占位",2,2,0,null,"NOT_TESTED",false,passwordEndpoint.createdAt(),Instant.now(),SandboxSshAuthType.KEY);
        assertThat(repository.update(keyEndpoint,1)).isTrue();
        assertThat(repository.find(tenant,passwordEndpoint.id()).orElseThrow().sshAuthType()).isEqualTo(SandboxSshAuthType.KEY);
        assertThat(repository.find(other,first.id())).isEmpty();assertThat(repository.list(other)).isEmpty();
        assertThat(repository.update(endpoint(other,first.id(),2),1)).isFalse();
        assertThat(repository.update(endpoint(tenant,first.id(),2),9)).isFalse();
        assertThat(repository.update(endpoint(tenant,first.id(),2),1)).isTrue();
        assertThatThrownBy(()->repository.atomic(tenant,()->{
            repository.setDefault(tenant,first.id());throw new IllegalStateException("模拟严格审计失败");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.defaultId(tenant)).isEmpty();
        assertThatThrownBy(()->repository.atomic(other,()->{repository.setDefault(other,first.id());return null;})).isInstanceOf(IllegalArgumentException.class);
        // 首次默认行尚不存在时，两个实例争用同一租户行锁，最终仅有一个有效默认选择。
        try(var workers=Executors.newFixedThreadPool(2)){
            var barrier=new CyclicBarrier(2);
            var a=workers.submit(()->{barrier.await();repository.atomic(tenant,()->{repository.setDefault(tenant,first.id());return null;});return null;});
            var b=workers.submit(()->{barrier.await();repository.atomic(tenant,()->{repository.setDefault(tenant,second.id());return null;});return null;});
            a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        assertThat(repository.defaultId(tenant).orElseThrow()).isIn(first.id(),second.id());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM skill_sandbox_defaults WHERE tenant_id=:id").param("id",tenant.toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM skill_sandbox_defaults WHERE tenant_id=:id AND created_at IS NOT NULL AND updated_at>=created_at")
            .param("id",tenant.toString()).query(Long.class).single()).isEqualTo(1);
        repository.atomic(tenant,()->{repository.setDefault(tenant,null);return null;});
        assertThat(repository.defaultId(tenant)).isEmpty();
    }
    private SandboxEndpoint endpoint(UUID tenant,UUID id,long revision){
        var now=Instant.now();return new SandboxEndpoint(id,tenant,"测试","docker",SandboxConnectionMode.LOCAL,"",0,"",true,"",0,revision,0,null,"NOT_TESTED",false,now,now);
    }
}
