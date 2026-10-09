package com.cmagent.server.web;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.SkillArtifactStorage;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/** 真实 Spring Security 与下载边界；不调用 Docker，不连接用户服务或模型。 */
@SpringBootTest(classes=CmAgentServerApplication.class,properties={"cm-agent.persistence.mode=memory","cm-agent.agentscope.studio.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SkillArtifactControllerTest {
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Path ROOT=privateRoot();
    private static Path privateRoot(){try{return Files.createTempDirectory("artifact-web-test-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("cm-agent.skills.sandbox.artifacts.enabled",()->"true");registry.add("cm-agent.skills.sandbox.artifacts.root-directory",ROOT::toString);
        registry.add("cm-agent.skills.sandbox.artifacts.encryption-key",()->Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired MockMvc mvc; @Autowired JwtService jwt; @Autowired RunRepository runs;
    @Autowired SkillArtifactRepository rows; @Autowired SkillArtifactStorage storage; @Autowired SkillTrialRepository trials;
    @Autowired com.cmagent.server.service.SkillUnitOfWork work;
    private String token(UUID tenant,String owner,String...permissions){return jwt.createToken(tenant,owner,"验收",List.of(permissions));}
    private SkillArtifact create(RunKind kind,Instant expiry)throws Exception{
        UUID agent=UUID.randomUUID(),run=UUID.randomUUID(),skill=UUID.randomUUID(),version=UUID.randomUUID();Instant now=Instant.now();
        runs.save(TENANT,new RunRecord(run,TENANT,agent,"owner",kind,RunStatus.SUCCEEDED,"","","",now,now));
        if(kind==RunKind.TEST)work.execute(()->trials.insert(new SkillTrial(run,TENANT,skill,version,agent,0,SkillTrialStatus.NOT_TRIGGERED,false,"owner",now,now)));
        var a=new SkillArtifact(UUID.randomUUID(),TENANT,"owner",agent,run,kind,skill,version,"call","中文文件.txt","text/plain",3,"",SkillArtifactStatus.STAGING,now,expiry,null,null);
        assertThat(rows.reserve(a,100,1000,64,4096)).isTrue();String hash=storage.write(a,new ByteArrayInputStream(new byte[]{1,2,3}));rows.stored(TENANT,a.id(),hash);rows.publish(TENANT,run,expiry);return rows.find(TENANT,a.id()).orElseThrow();
    }
    @Test void 当前认证与所有者权限矩阵及附件响应()throws Exception{
        var a=create(RunKind.NORMAL,Instant.now().plusSeconds(60));String path="/api/skill-artifacts/"+a.id()+"/content";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer "+token(TENANT,"owner"))).andExpect(status().isForbidden());
        for(String auth:List.of(token(TENANT,"other","agent:read"),token(UUID.randomUUID(),"owner","agent:read")))
            mvc.perform(get(path).header("Authorization","Bearer "+auth)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SKILL_ARTIFACT_NOT_FOUND")).andExpect(jsonPath("$.errorId").isNotEmpty());
        mvc.perform(get(path).header("Authorization","Bearer "+token(TENANT,"owner","agent:read"))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("attachment"))).andExpect(header().exists("X-Error-Id"))
            .andExpect(content().bytes(new byte[]{1,2,3}));
    }
    @Test void TEST额外技能权限和过期只对所有者返回410()throws Exception{
        var a=create(RunKind.TEST,Instant.now().minusSeconds(1));String path="/api/skill-artifacts/"+a.id()+"/content";
        mvc.perform(get(path).header("Authorization","Bearer "+token(TENANT,"owner","agent:read"))).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization","Bearer "+token(TENANT,"owner","agent:read","skill:read"))).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("SKILL_ARTIFACT_EXPIRED"));
        mvc.perform(get("/api/skills/"+a.skillId()+"/trials/"+a.runId()+"/artifacts").header("Authorization","Bearer "+token(TENANT,"owner","agent:read","skill:read")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.files[0].status").value("EXPIRED"));
        mvc.perform(get("/api/agents/"+a.agentId()+"/runs/"+a.runId()+"/artifacts").header("Authorization","Bearer "+token(TENANT,"owner","agent:read"))).andExpect(status().isNotFound());
    }
    @Test void 损坏密文不返回字节且唯一脱敏日志与响应编号一致()throws Exception{
        var a=create(RunKind.NORMAL,Instant.now().plusSeconds(60));Path file=ROOT.resolve(TENANT.toString()).resolve(a.id()+".gcm");byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(com.cmagent.server.runtime.SkillArtifactService.class);
        var captured=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();captured.start();logger.addAppender(captured);
        try{
        var response=mvc.perform(get("/api/skill-artifacts/"+a.id()+"/content").header("Authorization","Bearer "+token(TENANT,"owner","agent:read")))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SKILL_ARTIFACT_UNAVAILABLE")).andExpect(jsonPath("$.errorId").isNotEmpty())
            .andExpect(jsonPath("$.message",org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(ROOT.toString())))).andReturn().getResponse();
        String errorId=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString()).get("errorId").asText();
        var events=captured.list.stream().filter(e->e.getFormattedMessage().contains("errorId="+errorId)).toList();
        assertThat(events).hasSize(1);var event=events.getFirst();
        assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("SKILL_ARTIFACT_UNAVAILABLE",a.id().toString(),TENANT.toString()).doesNotContain(ROOT.toString(),"Bearer ");
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(event.getThrowableProxy().getMessage()).isEqualTo("文件产物操作失败");
        assertThat(response.getContentAsString()).doesNotContain(ROOT.toString(),".gcm","IllegalStateException","Bearer ");
        }finally{logger.detachAppender(captured);captured.stop();}
    }
    @AfterAll static void cleanup()throws IOException{try(var files=Files.walk(ROOT)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
}
