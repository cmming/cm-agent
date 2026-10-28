package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.*;
import com.cmagent.core.tool.*;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.config.WorkspaceContextProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Rocky显式门禁下使用真实Provider验证IMPORTED A/B、审批冻结和停止；只发送随机合成校验值。
 * Key仅取验证环境并交给既有加密器，断言不输出模型正文、凭据或外部响应。
 */
@SpringBootTest(classes=CmAgentServerApplication.class,properties={
        "cm-agent.fake-runtime-enabled=false","cm-agent.agentscope.enabled=true","cm-agent.agentscope.studio.enabled=false",
        "cm-agent.workspace-context.enabled=true","cm-agent.workspace-context.imports.enabled=true","cm-agent.persistence.mode=memory",
        "cm-agent.approval-expiry.enabled=false","cm-agent.agentscope.permission-enabled=true",
        "cm-agent.agentscope.model-max-attempts=1","cm-agent.agentscope.model-timeout=45s"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CM_AGENT_TEST_WORKSPACE_LIVE",matches="true")
class ImportedWorkspaceLiveProviderTest {
    /** 本例独占合成输入卷，生命周期不依赖Windows选中目录。 */
    private static final Path root=createRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        byte[] secret=new byte[32];new java.security.SecureRandom().nextBytes(secret);String key=Base64.getEncoder().encodeToString(secret);
        p.add("cm-agent.workspace-context.root-directory",()->root.resolve("reference").toString());
        p.add("cm-agent.workspace-context.imports.root-directory",()->root.resolve("imports").toString());
        p.add("cm-agent.workspace-context.imports.encryption-key",()->key);
        p.add("cm-agent.agentscope.execution-mode",()->System.getProperty("cm-agent.test.workspace.live.mode","react"));
    }
    @Autowired WorkspaceImportService imports;
    @Autowired WorkspaceContextProperties policy;
    @Autowired AgentDefinitionRepository agents;
    @Autowired ModelConfigRepository models;
    @Autowired ToolDefinitionRepository tools;
    @Autowired ToolGrantRepository grants;
    @Autowired ToolRegistry registry;
    @Autowired ModelCredentialCipher cipher;
    @Autowired ConversationService conversations;
    @Autowired ToolApprovalService approvals;
    @Autowired RunRepository runs;

    @Test void 真实Provider区分导入AB并保持审批及停止冻结语义(){
        String key=System.getenv("DASHSCOPE_API_KEY");assertTrue(key!=null&&!key.isBlank(),"验证环境未提供Provider Key");
        UUID tenant=UUID.fromString("00000000-0000-0000-0000-000000000001"),agent=UUID.randomUUID();
        String model=Optional.ofNullable(System.getenv("CM_AGENT_TEST_LIVE_MODEL")).orElse("qwen-plus");
        var config=models.save(new ModelConfig(UUID.randomUUID(),tenant,ModelProviderType.OPENAI_COMPATIBLE,"导入真实验收","https://dashscope.aliyuncs.com/compatible-mode/v1",model,true),cipher.encrypt(key));
        agents.save(new AgentDefinition(agent,tenant,"导入真实验收","仅合成资料","严格使用当前参考资料。用户要求使用native_probe时必须调用该工具后作答。",config.id(),model,0,6,true,List.of(),"tester","tester"));
        var owner=new PrincipalRef(tenant,"tester","合成验收主体",Set.of("agent:read","agent:run","agent:approve"));
        var effects=new AtomicInteger();
        var tool=tools.save(new ToolDefinition(UUID.randomUUID(),tenant,"native_probe","无副作用合成验收工具",ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",ToolRiskLevel.HIGH,true,"","tester","tester"));
        registry.register(tool,ignored->{effects.incrementAndGet();return ToolExecutionResult.succeeded("合成检查已完成",null);});grants.save(new ToolGrant(tenant,tool.id(),agent,"",true));
        String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
        var ba=ready(owner,agent,a);var bb=ready(owner,agent,b);
        for(var pair:List.of(Map.entry(ba,a),Map.entry(bb,b))){
            var c=conversations.create(owner,agent,new WorkspaceSelection(WorkspaceSelection.Mode.IMPORTED,pair.getKey().id()));
            var result=conversations.send(owner,agent,c.id(),"不调用工具，仅回复当前参考资料validation_code字段值。").run();
            assertEquals(RunStatus.SUCCEEDED,result.status(),"导入真实任务未成功");
            assertTrue(result.output().contains(pair.getValue()),"真实Provider没有使用当前导入随机资料");
            assertFalse(result.output().contains(pair.getValue().equals(a)?b:a),"真实Provider混入其他导入资料");
        }
        var frozen=conversations.create(owner,agent,new WorkspaceSelection(WorkspaceSelection.Mode.IMPORTED,ba.id()));
        var stopped=conversations.create(owner,agent,new WorkspaceSelection(WorkspaceSelection.Mode.IMPORTED,bb.id()));
        String ask="必须先调用native_probe工具，再仅回复参考资料validation_code的值。";
        assertEquals(RunStatus.WAITING_APPROVAL,conversations.send(owner,agent,frozen.id(),ask).run().status(),"真实Provider未触发审批");
        assertEquals(RunStatus.WAITING_APPROVAL,conversations.send(owner,agent,stopped.id(),ask).run().status(),"停止任务未进入审批");
        var pending=approvals.listPending(owner,agent,frozen.id()).getFirst();var stop=approvals.listPending(owner,agent,stopped.id()).getFirst();
        conversations.cancel(owner,agent,stopped.id(),stop.runId());assertEquals(RunStatus.CANCELLED,runs.findByTenantAndAgentAndId(tenant,agent,stop.runId()).orElseThrow().status());
        assertTrue(approvals.listPending(owner,agent,stopped.id()).isEmpty(),"停止后仍可见待审批项");assertEquals(0,effects.get());
        policy.getImports().setEnabled(false);
        var resumed=approvals.decideAndResume(owner,agent,frozen.id(),pending.approvalId(),pending.version(),pending.items().stream().map(i->new ToolApprovalService.ItemDecision(i.itemId(),ToolApprovalDecision.APPROVE)).toList(),ignored->{},ignored->{},ignored->{}).run();
        assertEquals(RunStatus.SUCCEEDED,resumed.status(),"旧快照审批恢复失败");assertTrue(resumed.output().contains(a),"审批未使用原导入资料");assertFalse(resumed.output().contains(b),"审批混入其他导入资料");assertEquals(1,effects.get());
    }
    private WorkspaceImportService.View ready(PrincipalRef owner,UUID agent,String code){
        String content="{\"validation_code\":\""+code+"\"}";byte[] body=content.getBytes(StandardCharsets.UTF_8);
        var draft=imports.execute(owner,agent,null,"CREATE",UUID.randomUUID().toString(),c->imports.create(c,new WorkspaceImportService.Create("合成验收资料",UUID.randomUUID(),true,List.of(new WorkspaceImportService.FileRequest("guide.json",body.length,WorkspaceContextResource.digest(content))))));
        var uploaded=imports.execute(owner,agent,draft.id(),"UPLOAD",UUID.randomUUID().toString(),c->imports.upload(c,draft.id(),draft.files().getFirst().id(),draft.version(),()->new ByteArrayInputStream(body),body.length,"application/octet-stream"));
        return imports.execute(owner,agent,draft.id(),"COMPLETE",UUID.randomUUID().toString(),c->imports.complete(c,draft.id(),uploaded.version()));
    }
    private static Path createRoot(){try{return Files.createTempDirectory(Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent().getParent(),"cm-agent-import-live-");}catch(Exception failure){throw new IllegalStateException("无法创建合成验收根");}}
    @AfterAll static void cleanup()throws Exception{
        // 只处理本类随机创建的根；Files.walk默认不跟随符号链接。
        try(var paths=Files.walk(root)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
}
