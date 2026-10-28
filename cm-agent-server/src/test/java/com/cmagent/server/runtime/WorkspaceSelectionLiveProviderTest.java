package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.config.WorkspaceContextProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 显式开启后使用环境中的验证 Key，走真实会话选择、文件读取、快照与 Provider。
 * 仅创建无敏感随机资料，不把凭据、模型正文或外部响应写入断言和产物；不代表其他模型质量。
 */
@SpringBootTest(classes=CmAgentServerApplication.class,properties={
        "cm-agent.fake-runtime-enabled=false","cm-agent.agentscope.enabled=true","cm-agent.agentscope.studio.enabled=false",
        "cm-agent.workspace-context.enabled=true","cm-agent.persistence.mode=memory","cm-agent.approval-expiry.enabled=false",
        "cm-agent.agentscope.model-max-attempts=1","cm-agent.agentscope.model-timeout=45s"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CM_AGENT_TEST_WORKSPACE_LIVE",matches="true")
class WorkspaceSelectionLiveProviderTest {
    /** 本测试独占驱动器根下的随机合成目录，不使用应用/用户目录或部署资料。 */
    private static final Path root=createRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("cm-agent.workspace-context.root-directory",root::toString);
        properties.add("cm-agent.agentscope.execution-mode",()->System.getProperty("cm-agent.test.workspace.live.mode","react"));
    }
    @Autowired WorkspaceContextProperties policy;
    @Autowired AgentDefinitionRepository agents;
    @Autowired ModelConfigRepository models;
    @Autowired ModelCredentialCipher cipher;
    @Autowired ConversationService conversations;
    @Autowired RunExecutionService execution;

    @Test void 真实Provider根据所选目录返回不同随机资料且NONE无注入() throws Exception {
        String key=System.getenv("DASHSCOPE_API_KEY");
        assertTrue(key!=null&&!key.isBlank(),"未提供验证 Key");
        UUID tenant=UUID.fromString("00000000-0000-0000-0000-000000000001"),agentId=UUID.randomUUID(),modelId=UUID.randomUUID();
        String model=Optional.ofNullable(System.getenv("CM_AGENT_TEST_LIVE_MODEL")).orElse("qwen-plus");
        models.save(new ModelConfig(modelId,tenant,ModelProviderType.OPENAI_COMPATIBLE,"工作区真实验收", "https://dashscope.aliyuncs.com/compatible-mode/v1",model,true),cipher.encrypt(key));
        agents.save(new AgentDefinition(agentId,tenant,"工作区真实验收","合成资料","仅按参考资料返回 validation_code，不猜测或补充。",modelId,model,0,3,true,List.of(),"tester","tester"));
        var principal=new PrincipalRef(tenant,"tester","验收主体",Set.of("agent:read","agent:run"));
        var entries=new ArrayList<WorkspaceContextProperties.Entry>();
        var codes=new ArrayList<String>();
        for(int i=0;i<2;i++) {
            String directory="reference-"+i,code=UUID.randomUUID().toString();codes.add(code);
            Files.createDirectory(root.resolve(directory));Files.writeString(root.resolve(directory).resolve("guide.json"),"{\"validation_code\":\""+code+"\"}");
            entries.add(new WorkspaceContextProperties.Entry(UUID.randomUUID(),"验收目录"+i,tenant,List.of(agentId),directory,List.of("guide.json"),true));
        }
        policy.setCatalog(entries);policy.validate();
        String input="仅回复当前参考资料 validation_code 字段的值。如果没有参考资料，只回复：无参考资料。";
        for(int i=0;i<2;i++) {
            var conversation=conversations.create(principal,agentId,new WorkspaceSelection(WorkspaceSelection.Mode.SELECTED,entries.get(i).id()));
            var result=conversations.send(principal,agentId,conversation.id(),input).run();
            assertTrue(result.status()==RunStatus.SUCCEEDED,"真实目录任务未成功，请按 Run 诊断核查");
            assertTrue(result.output().contains(codes.get(i)),"真实模型没有使用所选目录的随机资料");
            assertFalse(result.output().contains(codes.get(1-i)),"真实模型混入另一个目录资料");
        }
        var none=execution.run(principal,agentId,input,new WorkspaceSelection(WorkspaceSelection.Mode.NONE,null),ignored->{});
        assertTrue(none.status()==RunStatus.SUCCEEDED&&none.output().contains("无参考资料"),"NONE 真实验收未达到约定");
    }
    private static Path createRoot() {
        try {
            var directory=Files.createDirectory(Path.of(System.getProperty("user.dir")).toAbsolutePath().getRoot().resolve("cm-agent-workspace-live-"+UUID.randomUUID()));
            WorkspaceContextServiceTest.privateDirectory(directory);return directory;
        } catch(Exception failure) {throw new IllegalStateException("无法创建独占验收目录");}
    }
    @AfterAll static void cleanup() throws Exception {
        // 仅删除本测试创建的随机根，不跟随链接，不接触部署目录。
        try(var paths=Files.walk(root)) {for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
}
