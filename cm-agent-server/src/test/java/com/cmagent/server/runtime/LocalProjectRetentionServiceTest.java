package com.cmagent.server.runtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cmagent.core.domain.LocalProjectOperation;
import com.cmagent.core.repository.LocalProjectOperationRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.audit.AuditPersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.KeyGenerator;
import java.io.IOException;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 保留期纯单元合同；mock事务不代表JDBC回滚，真实发现隔离/分页由双方言集成测试验收。 */
class LocalProjectRetentionServiceTest {
    private final Instant now=Instant.parse("2026-10-30T00:00:00Z");
    private Store operations;
    private AuditAppender audit;
    private ModelCredentialCipher cipher;
    private LocalProjectChangeService changes;
    private TransactionTemplate transaction;
    private LocalProjectRetentionService service;

    @BeforeEach void setup() throws Exception {
        operations=new Store();audit=mock(AuditAppender.class);changes=mock(LocalProjectChangeService.class);
        var generator=KeyGenerator.getInstance("AES");generator.init(256);cipher=new ModelCredentialCipher(generator.generateKey());
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call->new SimpleTransactionStatus());
        transaction=new TransactionTemplate(manager);
        service=new LocalProjectRetentionService("node",Clock.fixed(now,ZoneOffset.UTC),operations,audit,transaction,cipher,changes);
    }

    @Test void 文件GC被调用且命令七态擦除而运行中保留并不改变摘要归属() throws Exception {
        when(changes.cleanupExpired()).thenReturn(3);
        var originals=new ArrayList<LocalProjectOperation>();
        for(var state:LocalProjectOperation.State.values())originals.add(add(state,now.minus(Duration.ofDays(8))));
        assertThat(service.cleanup()).isEqualTo(10);
        verify(changes).cleanupExpired();
        for(var original:originals){
            var saved=operations.values.get(original.id());
            assertThat(saved.digest()).isEqualTo(original.digest());
            assertThat(saved.tenantId()).isEqualTo(original.tenantId());
            assertThat(saved.ownerId()).isEqualTo(original.ownerId());
            assertThat(saved.projectId()).isEqualTo(original.projectId());
            assertThat(saved.runId()).isEqualTo(original.runId());
            if(original.state()==LocalProjectOperation.State.RUNNING){assertThat(saved).isEqualTo(original);continue;}
            assertThat(saved.encryptedPayload()).startsWith("v1:").isNotEqualTo(original.encryptedPayload());
            assertThat(cipher.decrypt(saved.encryptedPayload())).contains("\"expired\":true",original.id().toString()).doesNotContain("合成命令正文");
            assertThat(saved.state()).isEqualTo(Set.of(LocalProjectOperation.State.PROPOSED,LocalProjectOperation.State.WAITING_APPROVAL,
                    LocalProjectOperation.State.PREPARED).contains(original.state())?LocalProjectOperation.State.CANCELLED:original.state());
        }
        assertThat(mockingDetails(audit).getInvocations()).allSatisfy(call->assertThat(Arrays.toString(call.getArguments())).doesNotContain("合成命令正文"));
    }

    @Test void 恰满七天可擦除而少一秒和文件类意图不被命令清理触碰() {
        var exact=add(LocalProjectOperation.State.SUCCEEDED,now.minus(Duration.ofDays(7)));
        var newer=add(LocalProjectOperation.State.SUCCEEDED,now.minus(Duration.ofDays(7)).plusSeconds(1));
        var command=add(LocalProjectOperation.State.SUCCEEDED,now.minus(Duration.ofDays(8)));
        var file=new LocalProjectOperation(command.id(),command.tenantId(),command.ownerId(),command.projectId(),command.agentId(),command.conversationId(),
                command.runId(),LocalProjectOperation.Kind.FILE_CHANGE,command.digest(),command.encryptedPayload(),command.state(),command.version(),command.createdAt(),command.updatedAt());
        operations.values.put(file.id(),file);
        assertThat(service.cleanup()).isEqualTo(1);
        assertThat(cipher.decrypt(operations.values.get(exact.id()).encryptedPayload())).contains("\"expired\":true");
        assertThat(operations.values.get(newer.id())).isEqualTo(newer);
        assertThat(operations.values.get(file.id())).isEqualTo(file);
    }

    @Test void 派发抢先赢得版本时过期取消不能终止运行也不能擦除() {
        var prepared=add(LocalProjectOperation.State.PREPARED,now.minus(Duration.ofDays(8)));
        operations.claimBeforeCancel=prepared.id();
        assertThat(service.cleanup()).isZero();
        assertThat(operations.values.get(prepared.id()).state()).isEqualTo(LocalProjectOperation.State.RUNNING);
        assertThat(operations.values.get(prepared.id()).encryptedPayload()).isEqualTo(prepared.encryptedPayload());
        verifyNoInteractions(audit);
    }

    @Test void 首百条墓碑不会遮挡后续清单且遍历末尾重置后不重复擦除() {
        for(int i=1;i<=102;i++){
            var source=add(LocalProjectOperation.State.SUCCEEDED,now.minus(Duration.ofDays(8)));
            operations.values.remove(source.id());
            UUID id=new UUID(0,i);
            String payload=i<=100?cipher.encrypt("{\"expired\":true,\"operationId\":\""+id+"\"}"):source.encryptedPayload();
            operations.values.put(id,new LocalProjectOperation(id,source.tenantId(),source.ownerId(),source.projectId(),source.agentId(),source.conversationId(),
                    source.runId(),source.kind(),source.digest(),payload,source.state(),source.version(),source.createdAt(),source.updatedAt()));
        }
        assertThat(service.cleanup()).isZero();
        assertThat(service.cleanup()).isEqualTo(2);
        assertThat(service.cleanup()).isZero();
        assertThat(service.cleanup()).isZero();
        assertThat(operations.values.values()).allSatisfy(value->assertThat(cipher.decrypt(value.encryptedPayload())).contains("\"expired\":true"));
    }

    @Test void 文件GC失败严格上抛同编号安全错误且不继续命令发现() throws Exception {
        doThrow(new IOException("synthetic-private-path C:\\sensitive\\project")).when(changes).cleanupExpired();
        assertSafeFailure(()->service.scheduledCleanup());
        assertThat(operations.discoveries).isZero();
    }

    @Test void 命令发现和严格审计失败均不泄露原SQL路径或正文() {
        operations.discoveryFailure=new org.springframework.dao.DataAccessResourceFailureException("SELECT synthetic-secret FROM hidden; C:\\sensitive\\project");
        assertSafeFailure(service::cleanup);
        operations.discoveryFailure=null;
        add(LocalProjectOperation.State.SUCCEEDED,now.minus(Duration.ofDays(8)));
        doThrow(new AuditPersistenceException("合成命令正文 synthetic-secret",new IllegalStateException("SELECT private"))).when(audit)
                .appendControlled(any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
        assertSafeFailure(service::cleanup);
    }

    private void assertSafeFailure(Runnable action){
        var logger=(Logger)LoggerFactory.getLogger(LocalProjectRetentionService.class);
        var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{
            assertThatThrownBy(action::run).isInstanceOfSatisfying(LocalProjectRetentionService.RetentionFailure.class,failure->{
                assertThat(failure.errorId()).isNotBlank();
                assertThat(failure.getMessage()).contains("PROJECT_RETENTION_FAILED",failure.errorId()).doesNotContain("synthetic-secret","sensitive","SELECT","合成命令正文");
                assertThat(failure.getCause()).isNull();
                assertThat(appender.list).anySatisfy(event->assertThat(event.getFormattedMessage()).contains("errorId="+failure.errorId(),"errorCode=PROJECT_RETENTION_FAILED"));
            });
            assertThat(appender.list).allSatisfy(event->{
                assertThat(event.getFormattedMessage()).doesNotContain("synthetic-secret","sensitive","SELECT","合成命令正文");
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getMessage()).doesNotContain("synthetic-secret","sensitive","SELECT","合成命令正文");
                assertThat(event.getThrowableProxy().getCause()).isNull();
                assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
            });
        }finally{logger.detachAppender(appender);appender.stop();}
    }

    private LocalProjectOperation add(LocalProjectOperation.State state,Instant created){
        var value=new LocalProjectOperation(UUID.randomUUID(),UUID.randomUUID(),"Owner",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                LocalProjectOperation.Kind.COMMAND,"a".repeat(64),cipher.encrypt("合成命令正文"),state,1,created,created);
        operations.values.put(value.id(),value);return value;
    }

    /** 同步合同替身用于状态/CAS测试，不模拟数据库节点JOIN、事务回滚或多实例锁。 */
    private static final class Store implements LocalProjectOperationRepository{
        /** 仅本测试实例的合成操作，不接触真实命令或文件。 */
        private final Map<UUID,LocalProjectOperation> values=new HashMap<>();
        /** 用于验证文件GC失败后不继续发现。 */
        private int discoveries;
        /** 单次合成竞争者先赢得运行版本。 */
        private UUID claimBeforeCancel;
        /** 仅用于证明最终日志边界不复制SQL动态消息。 */
        private RuntimeException discoveryFailure;
        @Override public List<LocalProjectOperation> findCommandRetentionCandidates(String node,Instant cutoff,CommandRetentionCursor after,int limit){
            discoveries++;if(discoveryFailure!=null)throw discoveryFailure;
            assertThat(node).isEqualTo("node");
            var order=Comparator.comparing(LocalProjectOperation::createdAt).thenComparing(value->value.id().toString()).thenComparing(value->value.tenantId().toString());
            return values.values().stream().filter(value->value.kind()==LocalProjectOperation.Kind.COMMAND&&value.state()!=LocalProjectOperation.State.RUNNING&&!value.createdAt().isAfter(cutoff))
                    .filter(value->after==null||value.createdAt().isAfter(after.createdAt())||value.createdAt().equals(after.createdAt())
                            &&(value.id().toString().compareTo(after.operationId().toString())>0||value.id().equals(after.operationId())&&value.tenantId().toString().compareTo(after.tenantId().toString())>0))
                    .sorted(order).limit(limit).toList();
        }
        @Override public boolean insertOperation(LocalProjectOperation value){return values.putIfAbsent(value.id(),value)==null;}
        @Override public Optional<LocalProjectOperation> operation(UUID tenant,String owner,UUID id){return Optional.ofNullable(values.get(id)).filter(value->value.tenantId().equals(tenant)&&value.ownerId().equals(owner));}
        @Override public Optional<LocalProjectOperation> advance(UUID tenant,String owner,UUID id,long version,LocalProjectOperation.State next,Instant time){
            var old=operation(tenant,owner,id).orElse(null);if(old==null||old.version()!=version)return Optional.empty();
            if(id.equals(claimBeforeCancel)){claimBeforeCancel=null;values.put(id,old.advance(LocalProjectOperation.State.RUNNING,time));return Optional.empty();}
            var changed=old.advance(next,time);values.put(id,changed);return Optional.of(changed);
        }
        @Override public boolean erasePayload(UUID tenant,String owner,UUID id,long version,String replacement,Instant time){
            var old=operation(tenant,owner,id).orElse(null);
            if(old==null||old.version()!=version||old.createdAt().plus(Duration.ofDays(7)).isAfter(time)
                    ||!Set.of(LocalProjectOperation.State.SUCCEEDED,LocalProjectOperation.State.FAILED,LocalProjectOperation.State.CANCELLED,LocalProjectOperation.State.UNKNOWN).contains(old.state()))return false;
            values.put(id,new LocalProjectOperation(old.id(),old.tenantId(),old.ownerId(),old.projectId(),old.agentId(),old.conversationId(),old.runId(),old.kind(),old.digest(),replacement,
                    old.state(),old.version()+1,old.createdAt(),time));return true;
        }
    }
}
