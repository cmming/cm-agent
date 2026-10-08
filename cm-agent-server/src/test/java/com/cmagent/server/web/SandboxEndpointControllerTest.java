package com.cmagent.server.web;

import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.cmagent.core.repository.SandboxEndpointRepository;
import com.cmagent.server.audit.AuditAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=CmAgentServerApplication.class,properties={
    "cm-agent.skills.enabled=true","cm-agent.skills.sandbox.enabled=true",
    "cm-agent.persistence.mode=memory","cm-agent.agentscope.studio.enabled=false",
    "cm-agent.skills.sandbox.allowed-targets[0]=127.0.0.1:2222"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class SandboxEndpointControllerTest {
    @Test void 密码只写类型切换有独立权限且空密码与错误组合拒绝()throws Exception{
        String marker=" "+UUID.randomUUID()+" 密码测试 ";
        var body=new HashMap<String,Object>();
        body.put("displayName","密码端点");body.put("backend","docker");body.put("mode","SSH");body.put("sshAuthType","PASSWORD");
        body.put("host","127.0.0.1");body.put("port",2222);body.put("username","tester");body.put("enabled",true);body.put("revision",0);
        body.put("credentials",Map.of("password",marker,"knownHosts","测试信任"));
        String response=mvc.perform(post("/api/skill-sandbox-endpoints").header("Authorization",token(tenant,all)).contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.sshAuthType").value("PASSWORD")).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(marker,"password","encryptedCredential");
        String id=mapper.readTree(response).get("id").asText();
        body.remove("credentials");body.put("revision",1);body.put("sshAuthType","KEY");
        mvc.perform(put("/api/skill-sandbox-endpoints/"+id).header("Authorization",token(tenant,List.of("sandbox:write"))).contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/skill-sandbox-endpoints/"+id).header("Authorization",token(tenant,all)).contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKILL_SANDBOX_INVALID"));
        body.put("sshAuthType","PASSWORD");body.put("credentials",Map.of("password","","knownHosts","测试信任"));
        mvc.perform(post("/api/skill-sandbox-endpoints").header("Authorization",token(tenant,all)).contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().isBadRequest());
    }
    @Autowired MockMvc mvc;@Autowired JwtService jwt;@Autowired ObjectMapper mapper;
    @Autowired SandboxEndpointRepository repository;
    @MockitoSpyBean AuditAppender audit;
    @MockitoSpyBean com.cmagent.server.service.SandboxEndpointService endpoints;
    private final UUID tenant=UUID.randomUUID();
    private final List<String> all=List.of("sandbox:read","sandbox:write","sandbox:delete","sandbox:test","sandbox:credential:write");
    @Test void 密码探测受控失败状态和同编号日志且不暴露材料()throws Exception{
        String id=create();
        Logger logger=(Logger)LoggerFactory.getLogger(SandboxEndpointController.class);
        var capture=new ListAppender<ILoggingEvent>();capture.start();logger.addAppender(capture);
        try{
            for(var code:List.of(com.cmagent.api.ApiErrorCode.SKILL_SANDBOX_AUTH_FAILED,
                    com.cmagent.api.ApiErrorCode.SKILL_SANDBOX_TIMEOUT,com.cmagent.api.ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE)){
                capture.list.clear();
                // 传输的真实密码拒绝由Rocky集成测试证明；此处隔离验证HTTP/日志边界，避免本机联网。
                doThrow(new com.cmagent.core.runtime.SkillAccessException(code,"SSH连接失败，请核对配置",UUID.randomUUID().toString(),true))
                    .when(endpoints).probe(any(),eq(UUID.fromString(id)),eq(1L));
                int expected=code==com.cmagent.api.ApiErrorCode.SKILL_SANDBOX_AUTH_FAILED?502:
                    code==com.cmagent.api.ApiErrorCode.SKILL_SANDBOX_TIMEOUT?504:503;
                String response=mvc.perform(post("/api/skill-sandbox-endpoints/"+id+"/probe").header("Authorization",token(tenant,all))
                    .contentType("application/json").content("{\"revision\":1}"))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(code.name())).andReturn().getResponse().getContentAsString();
                String errorId=mapper.readTree(response).get("errorId").asText();
                assertThat(errorId).isNotBlank();
                assertThat(capture.list).anySatisfy(event->assertThat(event.getFormattedMessage()).contains(errorId,code.name(),tenant.toString(),id));
                assertThat(response+capture.list).doesNotContain("password=","Authorization","Bearer ","encryptedCredential","127.0.0.1");
            }
        }finally{reset(endpoints);logger.detachAppender(capture);}
    }
    @DynamicPropertySource static void key(DynamicPropertyRegistry registry){
        byte[] value=new byte[32];new java.security.SecureRandom().nextBytes(value);
        String encoded=Base64.getEncoder().encodeToString(value);
        registry.add("cm-agent.skills.sandbox.encryption-key",()->encoded);
    }
    private String token(UUID tenant,List<String> permissions){return "Bearer "+jwt.createToken(tenant,"tester","测试",permissions);}
    private String local(String name,long revision){return "{\"displayName\":\""+name+"\",\"backend\":\"docker\",\"mode\":\"LOCAL\",\"host\":\"\",\"port\":0,\"username\":\"\",\"enabled\":true,\"revision\":"+revision+"}";}
    private String create()throws Exception{
        String response=mvc.perform(post("/api/skill-sandbox-endpoints").header("Authorization",token(tenant,all))
            .contentType("application/json").content(local("测试端点",0))).andExpect(status().isCreated())
            .andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.encryptedCredential").doesNotExist()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("id").asText();
    }
    @Test void 匿名拒绝权限独立检查且不可跨租户引用()throws Exception{
        mvc.perform(get("/api/skill-sandbox-endpoints")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/skill-sandbox-endpoints").header("Authorization",token(tenant,List.of())))
            .andExpect(status().isForbidden());
        String id=create();
        String readOnly=token(tenant,List.of("sandbox:read"));
        mvc.perform(get("/api/skill-sandbox-endpoints/"+id).header("Authorization",readOnly)).andExpect(status().isOk());
        mvc.perform(post("/api/skill-sandbox-endpoints").header("Authorization",readOnly).contentType("application/json").content(local("只读禁止新增",0)))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/skill-sandbox-endpoints/"+id).header("Authorization",readOnly).contentType("application/json").content(local("只读禁止修改",1)))
            .andExpect(status().isForbidden());
        String other=token(UUID.randomUUID(),all);
        mvc.perform(get("/api/skill-sandbox-endpoints/"+id).header("Authorization",other))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SKILL_SANDBOX_ENDPOINT_NOT_FOUND"));
        mvc.perform(put("/api/skill-sandbox-endpoints/"+id).header("Authorization",other).contentType("application/json").content(local("跨租户更新",1)))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/skill-sandbox-endpoints/"+id+"?revision=1").header("Authorization",other))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api/skill-sandbox-endpoints/default").header("Authorization",other).contentType("application/json")
            .content("{\"endpointId\":\""+id+"\",\"revision\":1}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/skill-sandbox-endpoints").header("Authorization",token(tenant,all)).contentType("application/json")
            .content(local("忽略伪造租户",0).replace("{","{\"tenantId\":\""+UUID.randomUUID()+"\",")))
            .andExpect(status().isCreated());
        assertThat(repository.list(tenant)).hasSize(2);
        mvc.perform(delete("/api/skill-sandbox-endpoints/"+id+"?revision=1").header("Authorization",token(tenant,List.of("sandbox:write"))))
            .andExpect(status().isForbidden());
    }
    @Test void 配置CAS与当前版本探测约束返回正确错误码()throws Exception{
        String id=create();
        mvc.perform(put("/api/skill-sandbox-endpoints/"+id).header("Authorization",token(tenant,all)).contentType("application/json").content(local("新版",1)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.probeRevision").value(0));
        mvc.perform(put("/api/skill-sandbox-endpoints/"+id).header("Authorization",token(tenant,all)).contentType("application/json").content(local("旧版",1)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SKILL_SANDBOX_ENDPOINT_CONFLICT"));
        mvc.perform(put("/api/skill-sandbox-endpoints/default").header("Authorization",token(tenant,all)).contentType("application/json")
            .content("{\"endpointId\":\""+id+"\",\"revision\":2}")).andExpect(status().isConflict());
        mvc.perform(delete("/api/skill-sandbox-endpoints/"+id+"?revision=2").header("Authorization",token(tenant,all))).andExpect(status().isNoContent());
    }
    @Test void SSH认证加密落库不回显且替换需要独立权限()throws Exception{
        var body=new HashMap<String,Object>();
        body.put("displayName","SSH测试");body.put("backend","docker");body.put("mode","SSH");body.put("host","127.0.0.1");
        body.put("port",2222);body.put("username","test");body.put("enabled",true);body.put("revision",0);
        body.put("credentials",Map.of("privateKey","-----BEGIN PRIVATE KEY-----\n仅测试材料\n-----END PRIVATE KEY-----","knownHosts","仅测试信任"));
        String json=mapper.writeValueAsString(body);
        mvc.perform(post("/api/skill-sandbox-endpoints").header("Authorization",token(tenant,List.of("sandbox:write"))).contentType("application/json").content(json))
            .andExpect(status().isForbidden());
        String response=mvc.perform(post("/api/skill-sandbox-endpoints").header("Authorization",token(tenant,all)).contentType("application/json").content(json))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.hasCredential").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("PRIVATE KEY","仅测试材料","credentials","encryptedCredential","v1.");
        UUID id=UUID.fromString(mapper.readTree(response).get("id").asText());
        assertThat(repository.find(tenant,id).orElseThrow().encryptedCredential()).startsWith("v1.").doesNotContain("仅测试材料");
        mvc.perform(put("/api/skill-sandbox-endpoints/"+id).header("Authorization",token(tenant,List.of("sandbox:write"))).contentType("application/json").content(local("转为本地",1)))
            .andExpect(status().isForbidden());
    }
    @Test void 未知依赖失败有堆栈与同编号诊断且原文不出现在日志响应()throws Exception{
        Logger logger=(Logger)LoggerFactory.getLogger("com.cmagent.server.diagnostic.ErrorDiagnosticLogger");
        var capture=new ListAppender<ILoggingEvent>();capture.start();logger.addAppender(capture);
        String marker="测试泄漏标记";
        doThrow(new IllegalStateException("privateKey="+marker+" https://internal.example.test")).when(audit).append(any(),any(),eq("SANDBOX_CREATE"),any(),any(),any(),any());
        try{
            String response=mvc.perform(post("/api/skill-sandbox-endpoints").header("Authorization",token(tenant,all)).contentType("application/json").content(local("失败",0)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn().getResponse().getContentAsString();
            String errorId=mapper.readTree(response).get("errorId").asText();
            assertThat(errorId).isNotBlank();assertThat(response).doesNotContain(marker,"internal.example.test","IllegalStateException");
            assertThat(capture.list).anySatisfy(event->{assertThat(event.getFormattedMessage()).contains(errorId,tenant.toString(),"SANDBOX_MANAGEMENT","resourceType=SANDBOX_ENDPOINT","resourceId=create");assertThat(event.getThrowableProxy()).isNotNull();});
            assertThat(capture.list.toString()).doesNotContain(marker,"internal.example.test");
            assertThat(repository.list(tenant)).isEmpty();
        }finally{reset(audit);logger.detachAppender(capture);}
    }
}
