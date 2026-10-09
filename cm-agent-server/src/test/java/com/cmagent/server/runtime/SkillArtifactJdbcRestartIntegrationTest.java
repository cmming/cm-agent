package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.*;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.persistence.*;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Rocky 专用重建验收：固定 Python 回传后以 JDBC 提交 Run 和文件，
 * 丢弃首套 Service/Repository/存储实例，重新从数据库与同一持久卷下载。
 * 不连接真实模型；临时数据库身份由 Testcontainers 管理，不输出认证信息。
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named="CM_AGENT_TEST_SANDBOX",matches="true")
class SkillArtifactJdbcRestartIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
    @TempDir Path volume;
    @Test void PostgreSQL重新装配后鉴权下载()throws Exception{verify(postgres);}
    @Test void MySQL重新装配后鉴权下载()throws Exception{verify(mysql);}
    private void verify(JdbcDatabaseContainer<?> db)throws Exception{
        var properties=new SkillProperties();properties.getSandbox().setEnabled(true);
        var policy=properties.getSandbox().getArtifacts();policy.setEnabled(true);policy.setRootDirectory(volume.toString());
        policy.setEncryptionKey(Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32)));
        UUID tenant=UUID.randomUUID(),agent=UUID.randomUUID(),model=UUID.randomUUID(),run=UUID.randomUUID();
        var ds=new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());CmAgentFlyway.configure(ds).load().migrate();
        var jdbc=JdbcClient.create(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var now=java.sql.Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,:code,'重建验收租户',true,:now)").param("id",tenant.toString()).param("code",tenant.toString()).param("now",now).update();
        // 只建立运行外键所需的虚拟模型元数据；没有模型认证材料，也不会向此地址发请求。
        jdbc.sql("INSERT INTO model_configs(id,tenant_id,provider_type,display_name,base_url,model_name,encrypted_api_key,enabled,created_at) VALUES(:id,:tenant,'OPENAI','隔离占位','http://127.0.0.1:1','fixture','',true,:now)")
            .param("id",model.toString()).param("tenant",tenant.toString()).param("now",now).update();
        new JdbcAgentDefinitionRepository(jdbc,new com.fasterxml.jackson.databind.ObjectMapper(),tx).save(new AgentDefinition(agent,tenant,"重建验收 Agent","固定脚本","",model,"fixture",0.1,5,true,List.of(),"fixture","fixture"));
        var principal=new PrincipalRef(tenant,"fixture","验收",Set.of("agent:read"));
        UUID id=generate(jdbc,tx,properties,principal,agent,run);
        // 第二套对象不持有第一套引用；可信 Run 与文件元数据必须真正来自 JDBC。
        var reopenedDataSource=new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());
        var reopened=JdbcClient.create(reopenedDataSource);
        var restarted=service(reopened,new TransactionTemplate(new DataSourceTransactionManager(reopenedDataSource)),properties);
        var listing=restarted.list(principal,agent,run,null);assertThat(listing.status()).isEqualTo("READY");assertThat(listing.files()).hasSize(1);
        try(var download=restarted.download(principal,id)){
            var output=new ByteArrayOutputStream();download.write(output);
            assertThat(output.size()).isEqualTo(listing.files().getFirst().sizeBytes());
            assertThat(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(output.toByteArray()))).isEqualTo(listing.files().getFirst().sha256());
            try(var zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(output.toByteArray()))){var names=new HashSet<String>();for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())names.add(entry.getName());assertThat(names).contains("[Content_Types].xml","word/document.xml");}
        }
        assertThatThrownBy(()->restarted.download(new PrincipalRef(tenant,"other","其他",Set.of("agent:read")),id)).isInstanceOf(SkillAccessException.class);
    }
    private UUID generate(JdbcClient jdbc,TransactionTemplate tx,SkillProperties properties,PrincipalRef principal,UUID agent,UUID run)throws Exception{
        var runRepo=new JdbcRunRepository(jdbc);var running=RunRecord.create(run,principal.tenantId(),agent,principal.principalId(),"生成 DOCX",Instant.now());runRepo.save(principal.tenantId(),running);
        var artifacts=service(jdbc,tx,properties);var request=new SkillReadRequest(principal,agent,run,"restart-call",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"scripts/main.py");
        try(var collection=artifacts.begin(request);var execution=new DockerSkillSandbox(properties.getSandbox()).open(request,Map.of())){
            execution.execute(Map.of("scripts/main.py",Files.readString(Path.of("src/test/resources/skill-artifacts/scripts/main.py"))),"",collection);collection.complete();
        }
        var persistence=new RunPersistenceService(runRepo,new JdbcToolCallRepository(jdbc,tx),new AuditAppender(new JdbcAuditEventRepository(jdbc,tx)),new SensitiveDataRedactor(),tx);persistence.configureArtifacts(artifacts);
        persistence.complete(principal,running,new AgentRunResult(run,RunStatus.SUCCEEDED,"已生成固定文档",List.of(),running.startedAt(),Instant.now(),""),List.of());
        return new JdbcSkillArtifactRepository(jdbc,tx).list(principal.tenantId(),run).getFirst().id();
    }
    private SkillArtifactService service(JdbcClient jdbc,TransactionTemplate tx,SkillProperties properties){
        var beans=new StaticListableBeanFactory();beans.addBean("storage",new FileSystemSkillArtifactStorage(properties.getSandbox().getArtifacts()));
        return new SkillArtifactService(new JdbcSkillArtifactRepository(jdbc,tx),beans.getBeanProvider(SkillArtifactStorage.class),new JdbcRunRepository(jdbc),new JdbcSkillTrialRepository(jdbc,tx),new JdbcConversationRepository(jdbc),
            (principal,permission)->principal.permissions().contains(permission)?AuthorizationDecision.allow():AuthorizationDecision.deny("权限不足"),new AuditAppender(new JdbcAuditEventRepository(jdbc,tx)),properties);
    }
}
