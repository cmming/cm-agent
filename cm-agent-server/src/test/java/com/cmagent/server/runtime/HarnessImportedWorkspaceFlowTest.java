package com.cmagent.server.runtime;

import com.cmagent.api.*;
import com.cmagent.agentscope.AgentScopeRuntimeAdapter;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.*;
import com.cmagent.core.tool.*;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.config.WorkspaceContextProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 真实AgentScope Harness通过回环协议桩验证IMPORTED运行合同；测试检查实际模型请求中的参考输入，
 * 不以预设回环回答冒充真实Provider理解质量。资料、密钥、工具与目录均为本类合成并持有。
 */
@SpringBootTest(classes=CmAgentServerApplication.class,properties={
        "cm-agent.fake-runtime-enabled=false","cm-agent.agentscope.enabled=true","cm-agent.agentscope.execution-mode=harness",
        "cm-agent.agentscope.studio.enabled=false","cm-agent.agentscope.permission-enabled=true","cm-agent.approval-expiry.enabled=false",
        "cm-agent.agentscope.model-max-attempts=1","cm-agent.agentscope.model-timeout=10s","cm-agent.agentscope.tool-timeout=10s",
        "cm-agent.workspace-context.enabled=true","cm-agent.workspace-context.imports.enabled=true"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Import(HarnessImportedWorkspaceFlowTest.Configuration.class)
@ExtendWith(OutputCaptureExtension.class)
class HarnessImportedWorkspaceFlowTest {
    /** 应用/用户目录之外的随机合成根，不读取或清理部署资料。 */
    static final Path root=privateRoot();
    /** 独立随机输入卷密钥，只在测试内存传递，不输出。 */
    private static final String INPUT_KEY=inputKey();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("cm-agent.workspace-context.root-directory",()->root.resolve("reference").toString());
        p.add("cm-agent.workspace-context.imports.root-directory",()->root.resolve("imports").toString());
        p.add("cm-agent.workspace-context.imports.encryption-key",()->INPUT_KEY);
    }
    @Autowired WorkspaceContextProperties policy;
    @Autowired WorkspaceImportService imports;
    @Autowired WorkspaceImportStorage storage;
    @Autowired WorkspaceContextService workspaces;
    @Autowired ConversationService conversations;
    @Autowired ToolApprovalService approvals;
    @Autowired RunExecutionService execution;
    @Autowired RunRepository runs;
    @Autowired AgentDefinitionRepository agents;
    @Autowired ModelConfigRepository models;
    @Autowired ToolDefinitionRepository tools;
    @Autowired ToolGrantRepository grants;
    @Autowired ToolRegistry registry;
    @Autowired AgentRuntime runtime;
    @Autowired ModelCredentialCipher cipher;
    @Autowired ObjectMapper mapper;
    /** JDBC继承合同只通过Testcontainers临时库执行；memory时为空，不触发本机容器。 */
    @Autowired ObjectProvider<JdbcClient> jdbc;
    @SpyBean RuntimeCheckpointRepository checkpoints;
    @SpyBean WorkspaceSelectionRepository selections;
    @SpyBean WorkspaceImportRepository importRows;
    /** 原主体来自合成可信上下文，每例随机租户避免配额串扰。 */
    private PrincipalRef owner;
    /** 当前租户唯一Agent。 */
    private UUID agent;
    /** 每例独占模型端口及处理线程，用完关闭。 */
    private HttpServer server;
    private ExecutorService workers;
    /** 回环请求只在内存断言，不写日志或测试附件。 */
    private final List<String> requests=new CopyOnWriteArrayList<>();
    /** 响应只驱动真实Adapter的协议分支，不决定资料输入断言。 */
    private final Queue<String> responses=new ConcurrentLinkedQueue<>();
    /** 无副作用HIGH工具的实际执行计数。 */
    private final AtomicInteger effects=new AtomicInteger();

    @BeforeEach void setup()throws Exception{
        reset(checkpoints,selections,importRows);policy.setEnabled(true);policy.getImports().setEnabled(true);policy.setBindings(List.of());policy.setCatalog(List.of());
        owner=new PrincipalRef(UUID.randomUUID(),"import-owner","合成导入主体",Set.of("agent:read","agent:run","agent:approve"));
        if(jdbc.getIfAvailable()!=null)jdbc.getObject().sql("INSERT INTO tenants(id,code,name,enabled,created_at) VALUES(:id,:id,'合成导入租户',true,:now)")
                .param("id",owner.tenantId().toString()).param("now",java.sql.Timestamp.from(Instant.now())).update();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);workers=Executors.newCachedThreadPool();server.setExecutor(workers);
        server.createContext("/v1/chat/completions",exchange->{
            requests.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String response=responses.poll();byte[] bytes=("data: "+(response==null?"回环响应缺失":response)+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=UTF-8");exchange.sendResponseHeaders(response==null?500:200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
        var model=models.save(new ModelConfig(UUID.randomUUID(),owner.tenantId(),ModelProviderType.OPENAI_COMPATIBLE,"导入回环模型",
                "http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture",true));
        agent=agents.save(new AgentDefinition(UUID.randomUUID(),owner.tenantId(),"导入合同Agent","","原系统规则",model.id(),"fixture",0,6,true,List.of(),owner.principalId(),owner.principalId())).id();
        var tool=tools.save(new ToolDefinition(UUID.randomUUID(),owner.tenantId(),"echo","合成HIGH工具",ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",ToolRiskLevel.HIGH,true,"",owner.principalId(),owner.principalId()));
        registry.register(tool,ignored->{effects.incrementAndGet();return ToolExecutionResult.succeeded("工具完成",null);});
        grants.save(new ToolGrant(owner.tenantId(),tool.id(),agent,"",true));
        assertThat(runtime).isInstanceOf(AgentScopeRuntimeAdapter.class);
    }
    @AfterEach void close(){
        reset(checkpoints,selections,importRows);policy.getImports().setEnabled(true);policy.setEnabled(true);
        if(server!=null)server.stop(0);if(workers!=null)workers.shutdownNow();
    }
    @AfterAll static void cleanupRoot()throws Exception{
        // 路径由本类Files.createTempDirectory产生且固定；不跟随链接，不接受任何用户路径。
        if(Files.exists(root))try(var paths=Files.walk(root)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }

    @Test void 导入A和B实际进入各自模型数据并支持独立Run(CapturedOutput output)throws Exception{
        String a="IMPORTED_A_"+UUID.randomUUID(),b="IMPORTED_B_"+UUID.randomUUID();
        var first=ready(Map.of("a.txt",a));var second=ready(Map.of("b.txt",b));
        var ca=conversation(first);finish();var ra=conversations.send(owner,agent,ca,"仅使用参考资料").run();
        var cb=conversation(second);finish();var rb=conversations.send(owner,agent,cb,"仅使用参考资料").run();
        assertThat(ra.status()).isEqualTo(RunStatus.SUCCEEDED);assertThat(rb.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(runs.workspaceProtocol(owner.tenantId(),agent,ra.runId())).isEqualTo(3);
        assertThat(requests.get(0)).contains(a,"CM_WORKSPACE_REFERENCE_V1").doesNotContain(b,root.toString());
        assertThat(requests.get(1)).contains(b).doesNotContain(a);
        finish();var independent=execution.run(owner,agent,"独立导入运行",new WorkspaceSelection(WorkspaceSelection.Mode.IMPORTED,first.id()),ignored->{});
        assertThat(independent.status()).isEqualTo(RunStatus.SUCCEEDED);assertThat(requests.getLast()).contains(a).doesNotContain(b);
        for(var message:mapper.readTree(requests.getLast()).path("messages"))if(message.path("role").asText().equals("system"))assertThat(message.toString()).doesNotContain(a,b);
        assertThat(output.getAll()).doesNotContain(a,b,INPUT_KEY);
    }

    @Test void 本机源文件删除和关闭新上传后旧审批仍使用原快照()throws Exception{
        String marker="IMPORTED_FROZEN_"+UUID.randomUUID();
        Path clientSource=Files.createTempFile(root,"synthetic-client-",".txt");Files.writeString(clientSource,marker);
        var batch=ready(Map.of("guide.txt",Files.readString(clientSource)));var conversation=conversation(batch);var pending=waiting(conversation);
        var guard=checkpoints.find(owner.tenantId(),user(),pending.runId().toString(),WorkspaceContextService.GUARD).orElseThrow();
        assertThat(guard.stateType()).isEqualTo("cm-agent-workspace-guard-v3");
        Files.writeString(clientSource,"CLIENT_CHANGED_不应进入模型");Files.delete(clientSource);policy.getImports().setEnabled(false);finish();
        var result=approve(conversation,pending);
        assertThat(result.run().status()).isEqualTo(RunStatus.SUCCEEDED);assertThat(result.run().runId()).isEqualTo(pending.runId());
        assertThat(requests.getLast()).contains(marker).doesNotContain("CLIENT_CHANGED");assertThat(count(requests.getLast(),marker)).isEqualTo(1);assertThat(effects).hasValue(1);
    }

    @Test void 撤销原批次阻止审批恢复及副作用()throws Exception{
        var batch=ready(Map.of("a.txt","IMPORTED_REVOKED_"+UUID.randomUUID()));var conversation=conversation(batch);var pending=waiting(conversation);
        imports.execute(owner,agent,batch.id(),"REVOKE",UUID.randomUUID().toString(),c->imports.revoke(c,batch.id(),batch.version()));
        assertThatThrownBy(()->approve(conversation,pending)).isInstanceOfSatisfying(WorkspaceContextException.class,
                e->assertThat(e.code()).isEqualTo(ApiErrorCode.WORKSPACE_CONTEXT_REVOKED));
        assertThat(requests).hasSize(1);assertThat(effects).hasValue(0);
    }

    @Test void 过期原批次不因仍有快照而继续恢复()throws Exception{
        var batch=ready(Map.of("a.txt","IMPORTED_EXPIRED_"+UUID.randomUUID()));var conversation=conversation(batch);var pending=waiting(conversation);
        var scope=new WorkspaceImportScope(owner.tenantId(),owner.principalId(),agent);var original=importRows.find(scope,batch.id()).orElseThrow();
        // 固定期限属于服务端状态；用已过期且仍满足领域不变量的替身，避免等待真实30天。
        var expired=new WorkspaceImport(original.id(),original.scope(),original.displayName(),original.files(),original.manifestDigest(),original.state(),original.uploaded(),
                original.version(),original.createdAt(),original.createdAt().plusNanos(1),original.reclaimed(),original.requestId());
        doReturn(Optional.of(expired)).when(importRows).find(eq(scope),eq(batch.id()));
        assertThatThrownBy(()->approve(conversation,pending)).isInstanceOfSatisfying(WorkspaceContextException.class,
                e->assertThat(e.code()).isEqualTo(ApiErrorCode.WORKSPACE_CONTEXT_REVOKED));
        assertThat(requests).hasSize(1);assertThat(effects).hasValue(0);
    }

    @Test void 原密文缺失即使原checkpoint有效也不得重放()throws Exception{
        var batch=ready(Map.of("a.txt","IMPORTED_MISSING_"+UUID.randomUUID()));var conversation=conversation(batch);var pending=waiting(conversation);
        storage.delete(importRows.find(new WorkspaceImportScope(owner.tenantId(),owner.principalId(),agent),batch.id()).orElseThrow());
        assertThatThrownBy(()->approve(conversation,pending)).isInstanceOfSatisfying(WorkspaceContextException.class,
                e->assertThat(e.code()).isEqualTo(ApiErrorCode.WORKSPACE_CONTEXT_UNAVAILABLE));
        assertThat(requests).hasSize(1);assertThat(effects).hasValue(0);
    }

    @Test void v3运行拒绝v1或v2守卫而不进入模型()throws Exception{
        var batch=ready(Map.of("a.txt","IMPORTED_VERSION_"+UUID.randomUUID()));
        for(String type:List.of("cm-agent-workspace-guard-v1","cm-agent-workspace-guard-v2")){
            var conversation=conversation(batch);var pending=waiting(conversation);int before=requests.size();
            var guard=checkpoints.find(owner.tenantId(),user(),pending.runId().toString(),WorkspaceContextService.GUARD).orElseThrow();
            checkpoints.save(new RuntimeCheckpoint(guard.id(),guard.tenantId(),guard.userId(),guard.sessionId(),guard.stateKey(),type,false,guard.encryptedPayload(),guard.expiresAt(),guard.createdAt(),guard.updatedAt()));
            assertThatThrownBy(()->approve(conversation,pending)).isInstanceOfSatisfying(WorkspaceContextException.class,
                    e->assertThat(e.code()).isEqualTo(ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE));
            assertThat(requests).hasSize(before);assertThat(effects).hasValue(0);
        }
    }

    @Test void 选择行和两项检查点全丢仍由Run见证拒绝默认回退()throws Exception{
        var batch=ready(Map.of("a.txt","IMPORTED_LOST_"+UUID.randomUUID()));var conversation=conversation(batch);var pending=waiting(conversation);
        checkpoints.delete(owner.tenantId(),user(),pending.runId().toString(),WorkspaceContextService.KEY);
        checkpoints.delete(owner.tenantId(),user(),pending.runId().toString(),WorkspaceContextService.GUARD);
        doReturn(Optional.empty()).when(selections).run(eq(owner.tenantId()),eq(agent),eq(pending.runId()));
        assertThat(runs.workspaceProtocol(owner.tenantId(),agent,pending.runId())).isEqualTo(3);
        var run=runs.findByTenantAndAgentAndId(owner.tenantId(),agent,pending.runId()).orElseThrow();
        assertThatThrownBy(()->workspaces.prepare(owner,run,true)).isInstanceOfSatisfying(WorkspaceContextException.class,
                e->assertThat(e.code()).isEqualTo(ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE));
        assertThat(requests).hasSize(1);assertThat(effects).hasValue(0);
    }

    @Test void 有效加密的子集快照也必须匹配完整manifest()throws Exception{
        var files=new LinkedHashMap<String,String>();files.put("a.txt","IMPORTED_SUBSET_A_"+UUID.randomUUID());files.put("b.txt","IMPORTED_SUBSET_B_"+UUID.randomUUID());
        var batch=ready(files);var conversation=conversation(batch);var pending=waiting(conversation);
        var row=checkpoints.find(owner.tenantId(),user(),pending.runId().toString(),WorkspaceContextService.KEY).orElseThrow();
        var original=mapper.readValue(cipher.decrypt(row.encryptedPayload()),WorkspaceContextSnapshot.class);var subset=List.of(original.resources().getFirst());
        var changed=new WorkspaceContextSnapshot(original.tenantId(),original.agentId(),original.runId(),original.principalId(),original.mappingDigest(),subset,WorkspaceContextSnapshot.version(subset),original.createdAt());
        checkpoints.save(new RuntimeCheckpoint(row.id(),row.tenantId(),row.userId(),row.sessionId(),row.stateKey(),row.stateType(),false,cipher.encrypt(mapper.writeValueAsString(changed)),row.expiresAt(),row.createdAt(),row.updatedAt()));
        assertThatThrownBy(()->approve(conversation,pending)).isInstanceOfSatisfying(WorkspaceContextException.class,
                e->assertThat(e.code()).isEqualTo(ApiErrorCode.WORKSPACE_CONTEXT_SNAPSHOT_UNAVAILABLE));
        assertThat(requests).hasSize(1);assertThat(effects).hasValue(0);
    }

    @Test void 停止共享批次的一轮不删除另一轮快照或导入对象()throws Exception{
        String marker="IMPORTED_SHARED_"+UUID.randomUUID();var batch=ready(Map.of("a.txt",marker));var ca=conversation(batch);var cb=conversation(batch);
        var a=waiting(ca);var b=waiting(cb);conversations.cancel(owner,agent,ca,a.runId());
        assertThat(checkpoints.exists(owner.tenantId(),user(),a.runId().toString())).isFalse();
        assertThat(checkpoints.exists(owner.tenantId(),user(),b.runId().toString())).isTrue();
        var original=importRows.find(new WorkspaceImportScope(owner.tenantId(),owner.principalId(),agent),batch.id()).orElseThrow();
        assertThat(storage.contains(original,original.files().getFirst())).isTrue();finish();
        assertThat(approve(cb,b).run().status()).isEqualTo(RunStatus.SUCCEEDED);assertThat(requests.getLast()).contains(marker);assertThat(effects).hasValue(1);
    }

    private WorkspaceImportService.View ready(Map<String,String> files){
        var manifest=files.entrySet().stream().map(entry->new WorkspaceImportService.FileRequest(entry.getKey(),entry.getValue().getBytes(StandardCharsets.UTF_8).length,WorkspaceContextResource.digest(entry.getValue()))).toList();
        var created=imports.execute(owner,agent,null,"CREATE",UUID.randomUUID().toString(),c->imports.create(c,new WorkspaceImportService.Create("合成导入",UUID.randomUUID(),true,manifest)));
        var current=created;
        for(var file:current.files()){
            var before=current;byte[] bytes=files.get(file.relativePath()).getBytes(StandardCharsets.UTF_8);
            current=imports.execute(owner,agent,before.id(),"UPLOAD",UUID.randomUUID().toString(),c->imports.upload(c,before.id(),file.id(),before.version(),()->new ByteArrayInputStream(bytes),bytes.length,"application/octet-stream"));
        }
        var uploaded=current;return imports.execute(owner,agent,uploaded.id(),"COMPLETE",UUID.randomUUID().toString(),c->imports.complete(c,uploaded.id(),uploaded.version()));
    }
    private UUID conversation(WorkspaceImportService.View batch){return conversations.create(owner,agent,new WorkspaceSelection(WorkspaceSelection.Mode.IMPORTED,batch.id())).id();}
    private ToolApprovalService.ToolApprovalView waiting(UUID conversation){responses.add(toolResponse());assertThat(conversations.send(owner,agent,conversation,"合成导入审批输入").run().status()).isEqualTo(RunStatus.WAITING_APPROVAL);return approvals.listPending(owner,agent,conversation).getFirst();}
    private ToolApprovalService.ApprovalResumeOutcome approve(UUID conversation,ToolApprovalService.ToolApprovalView pending){return approvals.decideAndResume(owner,agent,conversation,pending.approvalId(),pending.version(),pending.items().stream().map(item->new ToolApprovalService.ItemDecision(item.itemId(),ToolApprovalDecision.APPROVE)).toList(),ignored->{},ignored->{},ignored->{});}
    private void finish(){responses.add("{\"id\":\"final\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"合成任务完成\"},\"finish_reason\":\"stop\"}]}");}
    private static String toolResponse(){return "{\"id\":\"call\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"echo-call\",\"type\":\"function\",\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}";}
    private String user(){return owner.tenantId()+":"+owner.principalId();}
    private static int count(String text,String part){return (text.length()-text.replace(part,"").length())/part.length();}
    private static String inputKey(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return Base64.getEncoder().encodeToString(bytes);}
    private static Path privateRoot(){
        try{Path current=Path.of(System.getProperty("user.dir")).toAbsolutePath();while(current.getParent()!=null&&!Files.exists(current.resolve(".git")))current=current.getParent();
            Path base=Files.createTempDirectory(current.getParent(),"cm-agent-imported-contract-");WorkspaceContextServiceTest.privateDirectory(base);return base;
        }catch(Exception failure){throw new IllegalStateException("无法创建独占导入测试根");}
    }
    @TestConfiguration(proxyBeanMethods=false)static class Configuration{
        /** 只发送给本类回环模型，不读取真实Key，不能作为生产认证配置。 */
        @Bean ModelCredentialProvider importedFixtureCredentials(){return (tenant,model)->new ModelCredential("invalid-loopback-key");}
    }
}
