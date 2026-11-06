package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.LocalProjectOperationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class LocalProjectCommandServiceTest {
    @TempDir Path temp;
    private LocalProjectDirectoryTickets.Root root;
    private LocalProjectBinding binding;
    private PrincipalRef owner;
    private Store store;
    private LocalProjectCommandService service;

    @BeforeEach void setup()throws Exception{
        UUID tenant=UUID.randomUUID();
        owner=new PrincipalRef(tenant,"Owner","测试",Set.of("agent:run","project:execute"));
        var tickets=new LocalProjectDirectoryTickets(Clock.systemUTC(),Duration.ofMinutes(2),1);
        UUID request=UUID.randomUUID();var ticket=tickets.issue(owner,request,temp);root=tickets.consume(owner,request,ticket.id());
        binding=new LocalProjectBinding(tenant,"Owner",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),WorkspaceContextResource.digest(root.identity()),"node",1,Instant.now());
        store=new Store();
        Path executable=Files.writeString(temp.resolve("fixture.exe"),"只用于提案，不执行");
        service=make(Map.of("fixture",new LocalProjectCommandService.Toolchain(executable,List.of(),Map.of(),Set.of("LANG"))));
    }
    @AfterEach void close(){service.close();}

    @Test void 提案持久密文绑定精确上下文但不进入批准或启动()throws Exception{
        var proposal=service.propose(owner,binding,root,request("fixture",List.of("--version"),1,1024));
        var operation=store.operation(owner.tenantId(),owner.principalId(),proposal.operationId()).orElseThrow();
        assertThat(operation.state()).isEqualTo(LocalProjectOperation.State.PROPOSED);
        assertThat(operation.encryptedPayload()).startsWith("v1:").doesNotContain("--version",temp.toString());
        assertThatThrownBy(()->service.dispatchPrepared(owner,binding,root,proposal.operationId(),1)).isInstanceOf(IllegalStateException.class);
    }
    @Test void 外部主体或缺少权限不能读取操作且不能借用工具链程序()throws Exception{
        var proposal=service.propose(owner,binding,root,request("fixture",List.of(),1,1024));
        var other=new PrincipalRef(owner.tenantId(),"owner","其他",owner.permissions());
        assertThatThrownBy(()->service.status(other,binding,proposal.operationId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.propose(owner,binding,root,request("unknown",List.of(),1,1024))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.propose(owner,binding,root,request("fixture",List.of("--token","raw-value"),1,1024)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void 构建文件变化使原精确批准失效且不会启动程序()throws Exception{
        Files.writeString(temp.resolve("pom.xml"),"原构建");
        var proposal=service.propose(owner,binding,root,request("fixture",List.of(),1,1024));prepared(proposal);
        Files.writeString(temp.resolve("pom.xml"),"新构建");
        assertThatThrownBy(()->service.dispatchPrepared(owner,binding,root,proposal.operationId(),3)).isInstanceOf(IllegalStateException.class);
        assertThat(store.operation(owner.tenantId(),"Owner",proposal.operationId()).orElseThrow().state()).isEqualTo(LocalProjectOperation.State.PREPARED);
    }
    @Test void 重建后运行意图只转结果未知且阻止新副作用()throws Exception{
        var proposal=service.propose(owner,binding,root,request("fixture",List.of(),1,1024));prepared(proposal);
        store.claimPrepared(owner.tenantId(),"Owner",proposal.operationId(),3,Instant.now());
        assertThat(service.status(owner,binding,proposal.operationId()).state()).isEqualTo(LocalProjectOperation.State.UNKNOWN);
        assertThatThrownBy(()->service.propose(owner,binding,root,request("fixture",List.of(),1,1024))).isInstanceOf(IllegalStateException.class);
    }
    @Test void 固定保留期到达即拒绝未被分批清理的旧批准清单()throws Exception{
        var proposal=service.propose(owner,binding,root,request("fixture",List.of(),1,1024));prepared(proposal);
        synchronized(store){
            var saved=store.values.get(proposal.operationId());
            store.values.put(saved.id(),new LocalProjectOperation(saved.id(),saved.tenantId(),saved.ownerId(),saved.projectId(),
                    saved.agentId(),saved.conversationId(),saved.runId(),saved.kind(),saved.digest(),saved.encryptedPayload(),
                    saved.state(),saved.version(),saved.createdAt().minus(Duration.ofDays(8)),saved.updatedAt()));
        }
        assertThatThrownBy(()->service.dispatchPrepared(owner,binding,root,proposal.operationId(),3))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("保留期限");
        assertThat(store.operation(owner.tenantId(),"Owner",proposal.operationId()).orElseThrow().state()).isEqualTo(LocalProjectOperation.State.PREPARED);
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void 真实命令输出脱敏并防重复派发()throws Exception{
        replaceJava();
        var proposal=service.propose(owner,binding,root,request("java",List.of("secret-output"),10,1024));prepared(proposal);
        service.dispatchPrepared(owner,binding,root,proposal.operationId(),3);
        var result=await(proposal.operationId());
        assertThat(result.state()).isEqualTo(LocalProjectOperation.State.SUCCEEDED);
        assertThat(result.stdout()).doesNotContain("synthetic-sensitive-value").contains("已脱敏");
        assertThat(result.stderr()).doesNotContain("synthetic-sensitive-token").contains("已脱敏");
        assertThatThrownBy(()->service.dispatchPrepared(owner,binding,root,proposal.operationId(),3)).isInstanceOf(IllegalStateException.class);
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void 达到输出上限仍排空管道并正常退出()throws Exception{
        replaceJava();
        var proposal=service.propose(owner,binding,root,request("java",List.of("flood"),10,128));prepared(proposal);
        service.dispatchPrepared(owner,binding,root,proposal.operationId(),3);
        var result=await(proposal.operationId());
        assertThat(result.state()).isEqualTo(LocalProjectOperation.State.SUCCEEDED);
        assertThat(result.truncated()).isTrue();
        assertThat(result.stdout().length()+result.stderr().length()).isLessThanOrEqualTo(128);
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void 超时实际终止进程且不重复启动()throws Exception{
        replaceJava();
        var proposal=service.propose(owner,binding,root,request("java",List.of("sleep",temp.resolve("child.pid").toString()),1,1024));prepared(proposal);
        service.dispatchPrepared(owner,binding,root,proposal.operationId(),3);
        var result=await(proposal.operationId());
        assertThat(result.state()).isEqualTo(LocalProjectOperation.State.FAILED);
        long pid=Long.parseLong(Files.readString(temp.resolve("child.pid")));
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }
    private void replaceJava()throws Exception{
        service.close();
        String cp=Path.of(LocalProjectProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        service=make(Map.of("java",new LocalProjectCommandService.Toolchain(Path.of(System.getProperty("java.home"),"bin","java.exe"),
                List.of("-Dfile.encoding=UTF-8","-cp",cp,LocalProjectProcessFixture.class.getName()),
                Map.of("SystemRoot",System.getenv("SystemRoot")),Set.of())));
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void 已结束输出缓存采用正文回收后的持久版本()throws Exception{
        replaceJava();
        var proposal=service.propose(owner,binding,root,request("java",List.of("secret-output"),10,1024));prepared(proposal);
        service.dispatchPrepared(owner,binding,root,proposal.operationId(),3);
        var result=await(proposal.operationId());
        assertThat(result.state()).isEqualTo(LocalProjectOperation.State.SUCCEEDED);
        synchronized(store){
            var saved=store.values.get(proposal.operationId());
            // 模拟保留策略擦除清单时递增版本；缓存仍可交出有界输出，但不得隐藏数据库新版本。
            store.values.put(saved.id(),new LocalProjectOperation(saved.id(),saved.tenantId(),saved.ownerId(),saved.projectId(),
                    saved.agentId(),saved.conversationId(),saved.runId(),saved.kind(),saved.digest(),saved.encryptedPayload(),
                    saved.state(),saved.version()+1,saved.createdAt(),saved.updatedAt()));
        }
        assertThat(service.status(owner,binding,proposal.operationId()).version()).isEqualTo(result.version()+1);
    }
    @Test @EnabledOnOs(OS.WINDOWS) @Timeout(20)
    void 终态持久化失败补记未知且人工终结不被输出缓存覆盖()throws Exception{
        replaceJava();
        store.failSuccess=true;
        var proposal=service.propose(owner,binding,root,request("java",List.of("secret-output"),10,1024));prepared(proposal);
        var cancellation=new com.cmagent.core.runtime.RunCancellation();
        service.dispatchPrepared(owner,binding,root,proposal.operationId(),3,cancellation);
        cancellation.awaitQuiescence();
        var unknown=service.status(owner,binding,proposal.operationId());
        assertThat(unknown.state()).isEqualTo(LocalProjectOperation.State.UNKNOWN);
        assertThat(store.operation(owner.tenantId(),"Owner",proposal.operationId()).orElseThrow().state())
                .isEqualTo(LocalProjectOperation.State.UNKNOWN);
        var closed=store.advance(owner.tenantId(),"Owner",proposal.operationId(),unknown.version(),LocalProjectOperation.State.CANCELLED,Instant.now()).orElseThrow();
        var result=service.status(owner,binding,proposal.operationId());
        assertThat(result.state()).isEqualTo(LocalProjectOperation.State.CANCELLED);
        assertThat(result.version()).isEqualTo(closed.version());
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void Run停止许可保持到专属Job实际退出而不是只发布信号()throws Exception{
        replaceJava();
        Path pidFile=temp.resolve("cancelled-child.pid");
        var proposal=service.propose(owner,binding,root,request("java",List.of("sleep",pidFile.toString()),10,1024));prepared(proposal);
        var cancellation=new com.cmagent.core.runtime.RunCancellation();
        service.dispatchPrepared(owner,binding,root,proposal.operationId(),3,cancellation);
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(!Files.exists(pidFile)&&System.nanoTime()<deadline)Thread.sleep(10);
        assertThat(Files.exists(pidFile)).isTrue();
        cancellation.request();
        var stopped=java.util.concurrent.CompletableFuture.runAsync(cancellation::awaitQuiescence);
        stopped.get(10,java.util.concurrent.TimeUnit.SECONDS);
        var result=await(proposal.operationId());
        assertThat(result.state()).isEqualTo(LocalProjectOperation.State.CANCELLED);
        long pid=Long.parseLong(Files.readString(pidFile));
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }
    private LocalProjectCommandService make(Map<String,LocalProjectCommandService.Toolchain> tools){
        return new LocalProjectCommandService(store,new ModelCredentialCipher(new javax.crypto.spec.SecretKeySpec(new byte[32],"AES")),
                new ObjectMapper().findAndRegisterModules(),Clock.systemUTC(),tools,(principal,b)->{},(b,id,action,digest)->{});
    }
    private LocalProjectCommandService.CommandRequest request(String tool,List<String> args,int timeout,int output){
        return new LocalProjectCommandService.CommandRequest(tool,args,"",Map.of(),timeout,output);
    }
    private void prepared(LocalProjectCommandService.Proposal proposal){
        store.advance(owner.tenantId(),"Owner",proposal.operationId(),1,LocalProjectOperation.State.WAITING_APPROVAL,Instant.now());
        store.advance(owner.tenantId(),"Owner",proposal.operationId(),2,LocalProjectOperation.State.PREPARED,Instant.now());
    }
    private LocalProjectCommandService.Status await(UUID id)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        LocalProjectCommandService.Status result;
        do{result=service.status(owner,binding,id);if(result.state()!=LocalProjectOperation.State.RUNNING)return result;Thread.sleep(10);}while(System.nanoTime()<deadline);
        throw new AssertionError("合成命令未在期限内退出");
    }
    /** 内存合同替身显式实现原子claim，不能使用默认失败关闭实现冒充数据库并发验收。 */
    private static final class Store implements LocalProjectOperationRepository{
        /** 仅本测试实例的合成意图，不持久化真实清单。 */
        private final Map<UUID,LocalProjectOperation> values=new HashMap<>();
        /** 仅模拟原生Job已结束后的一次终态持久化失败，不影响UNKNOWN补记。 */
        private boolean failSuccess;
        public synchronized boolean insertOperation(LocalProjectOperation value){return values.putIfAbsent(value.id(),value)==null;}
        public synchronized Optional<LocalProjectOperation> operation(UUID tenant,String owner,UUID id){return Optional.ofNullable(values.get(id)).filter(v->v.tenantId().equals(tenant)&&v.ownerId().equals(owner));}
        public synchronized Optional<LocalProjectOperation> advance(UUID tenant,String owner,UUID id,long version,LocalProjectOperation.State next,Instant now){
            if(failSuccess&&next==LocalProjectOperation.State.SUCCEEDED){failSuccess=false;throw new IllegalStateException("合成终态保存失败");}
            var value=operation(tenant,owner,id).orElse(null);if(value==null||value.version()!=version)return Optional.empty();
            var result=value.advance(next,now.isBefore(value.updatedAt())?value.updatedAt():now);values.put(id,result);return Optional.of(result);
        }
        public synchronized boolean hasUncertainOperation(UUID tenant,String owner,UUID project,UUID except){
            return values.values().stream().anyMatch(v->v.tenantId().equals(tenant)&&v.ownerId().equals(owner)&&v.projectId().equals(project)
                    &&!v.id().equals(except)&&(v.state()==LocalProjectOperation.State.RUNNING||v.state()==LocalProjectOperation.State.UNKNOWN));
        }
        public synchronized Optional<LocalProjectOperation> claimPrepared(UUID tenant,String owner,UUID id,long version,Instant now){
            var value=operation(tenant,owner,id).orElse(null);if(value==null||value.state()!=LocalProjectOperation.State.PREPARED||hasUncertainOperation(tenant,owner,value.projectId(),id))return Optional.empty();
            return advance(tenant,owner,id,version,LocalProjectOperation.State.RUNNING,now);
        }
    }
}

