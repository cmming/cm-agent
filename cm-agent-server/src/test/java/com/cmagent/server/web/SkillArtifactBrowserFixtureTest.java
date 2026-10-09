package com.cmagent.server.web;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.*;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.runtime.*;
import com.cmagent.server.service.SkillUnitOfWork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
/**
 * Rocky 独立浏览器夹具：固定 Python 生成文件，经真实存储、Run 终态与鉴权接口下载。
 * 仅使用 test profile 的一次性凭据与 memory 元数据，不连接用户服务、数据库或外部模型。
 * 释放文件或十分钟期限结束后退出；生产禁止使用该夹具账号与端口配置。
 */
@EnabledIfEnvironmentVariable(named="CM_AGENT_TEST_ARTIFACT_BROWSER",matches="true")
@SpringBootTest(classes=CmAgentServerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
    "server.port=18098","cm-agent.persistence.mode=memory","cm-agent.skills.enabled=true","cm-agent.skills.sandbox.enabled=true",
    "cm-agent.security.bootstrap-admin-enabled=true","cm-agent.security.bootstrap-admin-username=artifact-browser",
    "cm-agent.security.bootstrap-admin-password=isolated-artifact-browser-test-only","cm-agent.agentscope.studio.enabled=false"})
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(SkillArtifactBrowserFixtureTest.SlowDownloads.class)
class SkillArtifactBrowserFixtureTest {
    /** 仅独立浏览器夹具保留请求四秒，使并发按钮反馈能被实际观察；生产不会装配。 */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class SlowDownloads {
        @org.springframework.context.annotation.Bean
        org.springframework.web.filter.OncePerRequestFilter fixtureDownloadDelay(){
            return new org.springframework.web.filter.OncePerRequestFilter(){
                @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,jakarta.servlet.FilterChain chain)throws java.io.IOException,jakarta.servlet.ServletException{
                    if(request.getRequestURI().startsWith("/api/skill-artifacts/")&&request.getRequestURI().endsWith("/content")){
                        try{Thread.sleep(4000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException("测试夹具已停止");}
                    }
                    chain.doFilter(request,response);
                }
            };
        }
    }
    @DynamicPropertySource static void policy(DynamicPropertyRegistry registry){
        String key=Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
        registry.add("cm-agent.skills.sandbox.artifacts.enabled",()->"true");registry.add("cm-agent.skills.sandbox.artifacts.root-directory",()->"/tmp/cm-agent-artifact-browser-volume");registry.add("cm-agent.skills.sandbox.artifacts.encryption-key",()->key);
    }
    @Autowired AgentDefinitionRepository agents; @Autowired RunRepository runs; @Autowired SkillArtifactService artifacts;
    @Autowired RunPersistenceService persistence; @Autowired SkillProperties properties; @Autowired SkillUnitOfWork work;
    @Autowired SkillDefinitionRepository skills; @Autowired SkillVersionRepository versions; @Autowired SkillTrialRepository trials;
    @Autowired ConversationRepository conversations; @Autowired ConversationMessageRepository messages;
    @Test void 浏览器验收生命周期()throws Exception{
        UUID tenant=UUID.fromString("00000000-0000-0000-0000-000000000001"),agent=UUID.randomUUID(),skill=UUID.randomUUID(),version=UUID.randomUUID();String owner="artifact-browser";Instant now=Instant.now();
        agents.save(new AgentDefinition(agent,tenant,"文件产物验收 Agent","固定 Python 独立夹具","",UUID.randomUUID(),"fixture",0.1,5,true,List.of(),owner,owner));
        work.execute(()->{skills.insert(new SkillDefinition(skill,tenant,"文件产物验收技能",version,version,true,0,0,owner,owner,now,now));versions.insert(new SkillVersion(version,tenant,skill,1,"固定脚本验收",Map.of(),"固定 Python 输出文件","0".repeat(64),owner,now));return null;});
        var principal=new PrincipalRef(tenant,owner,"文件产物验收",Set.of("agent:read","skill:read"));
        UUID formal=generate(principal,agent,skill,version,RunKind.NORMAL);
        UUID test=generate(principal,agent,skill,version,RunKind.TEST);
        work.execute(()->trials.insert(new SkillTrial(test,tenant,skill,version,agent,0,SkillTrialStatus.NOT_TRIGGERED,false,owner,now,now)));
        UUID conversation=UUID.randomUUID();conversations.save(tenant,new Conversation(conversation,tenant,agent,"文件下载验收",owner,now,now));
        messages.append(tenant,new ConversationMessageDraft(UUID.randomUUID(),tenant,conversation,MessageRole.ASSISTANT,"验收 Agent",List.of(MessageContentBlock.text("已生成固定测试文档，可在下方下载。")),formal,Instant.now()));
        Path ready=Path.of("target/artifact-browser-ready"),release=Path.of("target/artifact-browser-release");Files.deleteIfExists(release);Files.writeString(ready,"就绪");
        try{long end=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(10);while(!Files.exists(release)&&System.nanoTime()<end)Thread.sleep(500);}finally{Files.deleteIfExists(ready);}
    }
    private UUID generate(PrincipalRef principal,UUID agent,UUID skill,UUID version,RunKind kind)throws Exception{
        Instant now=Instant.now();UUID id=UUID.randomUUID();var running=RunRecord.create(id,principal.tenantId(),agent,principal.principalId(),kind,"生成测试 DOCX",now);runs.save(principal.tenantId(),running);
        var request=new SkillReadRequest(principal,agent,id,"browser-call",UUID.randomUUID(),skill,version,"scripts/main.py");
        try(var collection=artifacts.begin(request);var execution=new DockerSkillSandbox(properties.getSandbox()).open(request,Map.of())){
            // 固定测试代码复制三份文件，仅验证多文件控件；不接受模型提交的脚本或路径。
            String script=Files.readString(Path.of("src/test/resources/skill-artifacts/scripts/main.py"))+"\nfor name in ('测试文档二.docx', '测试文档三.docx'):\n    output.with_name(name).write_bytes(output.read_bytes())\n";
            execution.execute(Map.of("scripts/main.py",script),"",collection);collection.complete();
        }
        persistence.complete(principal,running,new AgentRunResult(id,RunStatus.SUCCEEDED,"固定 Python 文档已生成。",List.of(),now,Instant.now(),""),List.of());return id;
    }
}
