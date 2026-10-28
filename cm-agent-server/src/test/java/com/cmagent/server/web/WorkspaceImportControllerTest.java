package com.cmagent.server.web;

import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.runtime.*;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.audit.*;
import com.cmagent.server.config.WorkspaceContextProperties;
import com.cmagent.server.runtime.*;
import com.cmagent.server.security.JwtService;
import com.cmagent.server.store.InMemoryPlatformStore;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实MVC/Security/仓储/密文链；只用合成资料和测试JWT，不调用外部模型或读用户目录。 */
@SpringBootTest(classes=CmAgentServerApplication.class,properties={"cm-agent.workspace-context.enabled=true","cm-agent.workspace-context.imports.enabled=true"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Import(WorkspaceImportControllerTest.RuntimeConfig.class)
class WorkspaceImportControllerTest {
    /** 类独占的合成私有目录，测试结束由JUnit回收。 */
    @TempDir static Path temp;
    /** 内存随机测试密钥，不落配置、日志或命令行。 */
    private static final String KEY=key();
    /** 当前合成租户，与默认平台种子一致，但所有Agent/owner按用例隔离。 */
    private static final UUID TENANT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired InMemoryPlatformStore platform;
    @Autowired WorkspaceContextProperties policy;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationMessageRepository messages;
    @Autowired WorkspaceSelectionRepository selections;
    @Autowired ToolApprovalService approvals;
    @SpyBean WorkspaceImportRepository rows;
    @SpyBean AuditAppender audit;
    @SpyBean WorkspaceImportBodyReader reader;
    /** 每用例独立可信主体，防止其他用例保留DRAFT影响配额。 */
    private String owner;
    /** 用例独占Agent，避免脏部署配置参与资料读取。 */
    private UUID agent;
    /** 验证响应与日志同号，收集器绝不记录请求体/Token。 */
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs;
    /** 当前最终边界logger，测试后必须移除收集器。 */
    private ch.qos.logback.classic.Logger logger;
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry){
        registry.add("cm-agent.workspace-context.root-directory",()->temp.resolve("registered").toString());
        registry.add("cm-agent.workspace-context.imports.root-directory",()->temp.resolve("imports").toString());
        registry.add("cm-agent.workspace-context.imports.encryption-key",()->KEY);
    }
    private static String key(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getEncoder().encodeToString(b);}
    @BeforeEach void setup(){
        reset(audit,reader,rows);policy.getImports().setEnabled(true);owner="owner-"+UUID.randomUUID();agent=UUID.randomUUID();
        platform.saveAgent(new AgentDefinition(agent,TENANT,"合成Agent","","只供接口测试",UUID.fromString("00000000-0000-0000-0000-000000000301"),"fake",0,4,true,List.of(),owner,owner));
        logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(WorkspaceImportService.class);
        logs=new ch.qos.logback.core.read.ListAppender<>();logs.start();logger.addAppender(logs);
    }
    @AfterEach void cleanup(){logger.detachAppender(logs);policy.getImports().setEnabled(true);reset(audit,reader,rows);}
    private String base(){return "/api/agents/"+agent+"/workspace-imports";}
    private String token(String who){return jwt.createToken(TENANT,who,"合成主体",List.of("agent:read","agent:run"));}
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request,String who){return request.header("Authorization","Bearer "+token(who));}
    private JsonNode result(MockHttpServletRequestBuilder request,int status) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    private JsonNode fail(MockHttpServletRequestBuilder request,int status,String code) throws Exception {
        var response=result(request,status);String id=response.path("errorId").asText();assertThat(id).isNotBlank();assertThat(response.path("code").asText()).isEqualTo(code);
        if(!code.equals("UNAUTHORIZED"))assertThat(logs.list).anySatisfy(event->assertThat(event.getFormattedMessage()).contains("errorId="+id,code));
        assertThat(response.toString()).doesNotContain(KEY,"SYNTHETIC_PRIVATE","https://internal.invalid","Exception","stackTrace");return response;
    }
    private Object manifest(UUID request,byte[] content) throws Exception {
        return Map.of("displayName","合成目录","requestId",request.toString(),"confirmed",true,"files",List.of(Map.of("relativePath","a.txt","sizeBytes",content.length,"sha256",hash(content))));
    }
    private static String hash(byte[] content)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));}
    private JsonNode create(byte[] content)throws Exception{return create(UUID.randomUUID(),content);}
    private JsonNode create(UUID request,byte[] content)throws Exception{return result(auth(post(base()),owner).contentType("application/json").content(json.writeValueAsBytes(manifest(request,content))),201);}
    private JsonNode upload(JsonNode batch,byte[] content)throws Exception{return result(auth(post(base()+"/"+batch.path("id").asText()+"/files/"+batch.path("files").get(0).path("id").asText()).param("expectedVersion",batch.path("version").asText()),owner).contentType("application/octet-stream").content(content),200);}
    private JsonNode complete(JsonNode batch)throws Exception{return result(auth(post(base()+"/"+batch.path("id").asText()+"/complete"),owner).contentType("application/json").content("{\"expectedVersion\":"+batch.path("version").asLong()+"}"),200);}

    @Test void 能力预加载不含文件资料且READY可绑定受控空会话()throws Exception{
        var caps=result(auth(get(base()+"/capabilities"),owner),200);
        assertThat(caps.path("enabled").asBoolean()).isTrue();assertThat(caps.path("maxFiles").asInt()).isEqualTo(4);assertThat(caps.path("runtimeAvailable").asBoolean()).isTrue();assertThat(caps.has("files")).isFalse();
        byte[] body="合成参考A".getBytes(java.nio.charset.StandardCharsets.UTF_8);UUID request=UUID.randomUUID();
        var batch=create(request,body);assertThat(batch.path("state").asText()).isEqualTo("DRAFT");
        var byRequest=result(auth(get(base()+"/by-request/"+request),owner),200);assertThat(byRequest.path("id")).isEqualTo(batch.path("id"));
        fail(auth(post(base()),owner).contentType("application/json").content(json.writeValueAsBytes(manifest(request,body))),409,"WORKSPACE_IMPORT_CONFLICT");
        var uploaded=upload(batch,body);var ready=complete(uploaded);assertThat(ready.path("state").asText()).isEqualTo("READY");assertThat(ready.path("ready").asBoolean()).isTrue();assertThat(ready.path("bindable").asBoolean()).isTrue();
        var created=result(auth(post("/api/agents/"+agent+"/conversations"),owner).contentType("application/json").content("{\"workspaceSelection\":{\"mode\":\"IMPORTED\",\"workspaceId\":\""+ready.path("id").asText()+"\"}}"),200);
        var bound=result(auth(get("/api/agents/"+agent+"/conversations/"+created.path("id").asText()+"/workspace"),owner),200);
        assertThat(bound.path("available").asBoolean()).isTrue();assertThat(bound.path("selection").path("mode").asText()).isEqualTo("IMPORTED");
        policy.getImports().setEnabled(false);
        result(auth(get(base()+"/"+ready.path("id").asText()),owner),200);
        fail(auth(post(base()),owner).contentType("application/json").content(json.writeValueAsBytes(manifest(UUID.randomUUID(),body))),409,"WORKSPACE_IMPORT_DISABLED");
        var revoked=result(auth(delete(base()+"/"+ready.path("id").asText()).param("expectedVersion",ready.path("version").asText()),owner),200);
        assertThat(revoked.path("state").asText()).isEqualTo("REVOKED");assertThat(revoked.path("ready").asBoolean()).isFalse();
    }
    @Test void 跨主体租户智能体和未知标识统一404且上传前不读取正文()throws Exception{
        byte[] body={'a'};var batch=create(body);String id=batch.path("id").asText(),file=batch.path("files").get(0).path("id").asText();
        clearInvocations(reader);
        for(String who:List.of("other-"+UUID.randomUUID(),owner.toUpperCase(Locale.ROOT))){
            fail(auth(get(base()+"/"+id),who),404,"WORKSPACE_IMPORT_NOT_FOUND");
            fail(auth(get(base()+"/by-request/"+batch.path("requestId").asText()),who),404,"WORKSPACE_IMPORT_NOT_FOUND");
            fail(auth(post(base()+"/"+id+"/files/"+file).param("expectedVersion","1"),who).contentType("application/octet-stream").content(body),404,"WORKSPACE_IMPORT_NOT_FOUND");
            fail(auth(delete(base()+"/"+id).param("expectedVersion","1"),who),404,"WORKSPACE_IMPORT_NOT_FOUND");
        }
        verify(reader,never()).read(any(),anyInt(),any());
        String otherTenant=jwt.createToken(UUID.randomUUID(),owner,"合成",List.of("agent:read","agent:run"));
        fail(get(base()+"/"+id).header("Authorization","Bearer "+otherTenant),404,"WORKSPACE_IMPORT_NOT_FOUND");
        fail(auth(get("/api/agents/"+UUID.randomUUID()+"/workspace-imports/"+id),owner),404,"WORKSPACE_IMPORT_NOT_FOUND");
        fail(auth(get(base()+"/"+UUID.randomUUID()),owner),404,"WORKSPACE_IMPORT_NOT_FOUND");
    }
    @Test void manifest明确确认路径类型和预算均由服务端拒绝()throws Exception{
        for(String path:List.of("../a.txt","C:/private/a.txt",".ssh/a.txt","a.exe")){
            var request=Map.of("displayName","合成目录","requestId",UUID.randomUUID(),"confirmed",true,"files",List.of(Map.of("relativePath",path,"sizeBytes",1,"sha256","a".repeat(64))));
            fail(auth(post(base()),owner).contentType("application/json").content(json.writeValueAsBytes(request)),400,"WORKSPACE_IMPORT_INVALID");
        }
        var unknown=new HashMap<String,Object>((Map<String,Object>)manifest(UUID.randomUUID(),new byte[]{'a'}));unknown.put("tenantId",UUID.randomUUID());
        fail(auth(post(base()),owner).contentType("application/json").content(json.writeValueAsBytes(unknown)),400,"WORKSPACE_IMPORT_INVALID");
        unknown.remove("tenantId");unknown.put("confirmed",false);
        fail(auth(post(base()),owner).contentType("application/json").content(json.writeValueAsBytes(unknown)),400,"WORKSPACE_IMPORT_INVALID");
        fail(auth(post(base()),owner).contentType("application/json").content(json.writeValueAsBytes(manifest(UUID.randomUUID(),new byte[20000]))),422,"WORKSPACE_IMPORT_CONTENT_INVALID");
        fail(auth(post(base()),owner).contentType("application/json").content(" ".repeat(16385)),413,"WORKSPACE_IMPORT_TOO_LARGE");
    }
    @Test void 文件实长摘要编码缺失重复与版本冲突不发布READY()throws Exception{
        var batch=create(new byte[]{'a'});String path=base()+"/"+batch.path("id").asText();String file=batch.path("files").get(0).path("id").asText();
        fail(auth(post(path+"/complete"),owner).contentType("application/json").content("{\"expectedVersion\":1}"),409,"WORKSPACE_IMPORT_CONFLICT");
        fail(auth(post(path+"/files/"+file).param("expectedVersion","99"),owner).contentType("application/octet-stream").content("a"),409,"WORKSPACE_IMPORT_CONFLICT");
        fail(auth(post(path+"/files/"+file).param("expectedVersion","1"),owner).contentType("application/octet-stream").content("b"),422,"WORKSPACE_IMPORT_CONTENT_INVALID");
        fail(auth(post(path+"/files/"+file).param("expectedVersion","1"),owner).contentType("application/octet-stream").content(new byte[16385]),413,"WORKSPACE_IMPORT_TOO_LARGE");
        for(byte[] invalid:List.of(new byte[]{0},new byte[]{(byte)255})){
            var invalidBatch=create(invalid);String invalidPath=base()+"/"+invalidBatch.path("id").asText()+"/files/"+invalidBatch.path("files").get(0).path("id").asText();
            fail(auth(post(invalidPath).param("expectedVersion","1"),owner).contentType("application/octet-stream").content(invalid),422,"WORKSPACE_IMPORT_CONTENT_INVALID");
        }
        var saved=upload(batch,new byte[]{'a'});
        fail(auth(post(path+"/files/"+file).param("expectedVersion",saved.path("version").asText()),owner).contentType("application/octet-stream").content("a"),409,"WORKSPACE_IMPORT_CONFLICT");
        assertThat(result(auth(get(path),owner),200).path("state").asText()).isEqualTo("DRAFT");
    }
    @Test void 超时配额权限与参数失败均保留准确状态和错误编号()throws Exception{
        fail(get(base()+"/capabilities"),401,"UNAUTHORIZED");
        String limited=jwt.createToken(TENANT,owner,"合成",List.of("agent:read"));
        fail(get(base()+"/capabilities").header("Authorization","Bearer "+limited),403,"FORBIDDEN");
        var batch=create(new byte[]{'a'});
        doThrow(new TimeoutException()).when(reader).read(any(),anyInt(),any());
        fail(auth(post(base()+"/"+batch.path("id").asText()+"/files/"+batch.path("files").get(0).path("id").asText()).param("expectedVersion","1"),owner).contentType("application/octet-stream").content("a"),503,"WORKSPACE_IMPORT_UPLOAD_TIMEOUT");
        reset(reader);
        for(int i=0;i<7;i++)create(new byte[]{'a'});
        fail(auth(post(base()),owner).contentType("application/json").content(json.writeValueAsBytes(manifest(UUID.randomUUID(),new byte[]{'a'}))),429,"WORKSPACE_IMPORT_LIMIT_EXCEEDED");
        var validation=result(auth(post(base()+"/"+batch.path("id").asText()+"/files/"+batch.path("files").get(0).path("id").asText()).param("expectedVersion","not-a-number"),owner),400);
        assertThat(validation.path("code").asText()).isEqualTo("WORKSPACE_IMPORT_INVALID");assertThat(validation.path("errorId").asText()).isNotBlank();
    }
    @Test void 审计失败补偿为撤销并可按请求找回且日志不带底层秘密()throws Exception{
        UUID request=UUID.randomUUID();
        doThrow(new AuditPersistenceException("SYNTHETIC_PRIVATE https://internal.invalid Bearer secret-value",new IllegalStateException("SYNTHETIC_PRIVATE")))
                .when(audit).appendControlled(any(),anyString(),eq("WORKSPACE_IMPORT_CREATE"),anyString(),anyString(),eq("SUCCEEDED"),anyString());
        fail(auth(post(base()),owner).contentType("application/json").content(json.writeValueAsBytes(manifest(request,new byte[]{'a'}))),503,"AUDIT_UNAVAILABLE");
        reset(audit);
        var recovered=result(auth(get(base()+"/by-request/"+request),owner),200);assertThat(recovered.path("state").asText()).isEqualTo("REVOKED");
        for(var event:logs.list){assertThat(event.getFormattedMessage()).doesNotContain("SYNTHETIC_PRIVATE","secret-value");if(event.getThrowableProxy()!=null)assertThat(event.getThrowableProxy().getMessage()).doesNotContain("SYNTHETIC_PRIVATE","internal.invalid");}
    }
    @Test void 草稿绑定被拒绝跨owner不泄露且过期和密文损坏有准确错误()throws Exception{
        var batch=create(new byte[]{'a'});String id=batch.path("id").asText();String selection="{\"workspaceSelection\":{\"mode\":\"IMPORTED\",\"workspaceId\":\""+id+"\"}}";
        String conversationsPath="/api/agents/"+agent+"/conversations";
        fail(auth(post(conversationsPath),owner).contentType("application/json").content(selection),409,"WORKSPACE_IMPORT_CONFLICT");
        fail(auth(post(conversationsPath),"other-"+UUID.randomUUID()).contentType("application/json").content(selection),404,"WORKSPACE_IMPORT_NOT_FOUND");
        var now=Instant.now();var scope=new WorkspaceImportScope(TENANT,owner,agent);
        var expired=WorkspaceImport.draft(UUID.randomUUID(),scope,"合成过期",List.of(new WorkspaceImportFile(UUID.randomUUID(),"a.txt",1,hash(new byte[]{'a'}))),now.minusSeconds(120),now.minusSeconds(60));
        rows.reserve(expired,policy.getImports().limits(policy),now.minusSeconds(120));
        fail(auth(post(base()+"/"+expired.id()+"/files/"+expired.files().getFirst().id()).param("expectedVersion","1"),owner).contentType("application/octet-stream").content("a"),410,"WORKSPACE_IMPORT_EXPIRED");
        var uploaded=upload(batch,new byte[]{'a'});var object=temp.resolve("imports").resolve(TENANT.toString()).resolve(id).resolve(batch.path("files").get(0).path("id").asText()+".gcm");
        byte[] cipher=Files.readAllBytes(object);cipher[cipher.length-1]^=1;Files.write(object,cipher);
        var response=fail(auth(post(base()+"/"+id+"/complete"),owner).header("X-Request-Id","T10-CORRUPT-CASE").contentType("application/json").content("{\"expectedVersion\":"+uploaded.path("version").asLong()+"}"),503,"WORKSPACE_IMPORT_UNAVAILABLE");
        assertThat(response.path("errorId").asText()).isEqualTo("T10-CORRUPT-CASE");
        assertThat(result(auth(get(base()+"/"+id),owner),200).path("state").asText()).isEqualTo("DRAFT");
    }
    @Test void READY成功审计失败不能留下可使用批次()throws Exception{
        var uploaded=upload(create(new byte[]{'a'}),new byte[]{'a'});String id=uploaded.path("id").asText();
        doThrow(new AuditPersistenceException("合成审计故障",new IllegalStateException("SYNTHETIC_PRIVATE")))
                .when(audit).appendControlled(any(),anyString(),eq("WORKSPACE_IMPORT_COMPLETE"),anyString(),anyString(),eq("SUCCEEDED"),anyString());
        fail(auth(post(base()+"/"+id+"/complete"),owner).contentType("application/json").content("{\"expectedVersion\":"+uploaded.path("version").asLong()+"}"),503,"AUDIT_UNAVAILABLE");
        reset(audit);assertThat(result(auth(get(base()+"/"+id),owner),200).path("state").asText()).isEqualTo("REVOKED");
    }
    @Test void 落盘后确认失败的未知上传可通过显式complete恢复而不重复上传()throws Exception{
        var batch=create(new byte[]{'a'});String id=batch.path("id").asText();
        doThrow(new IllegalStateException("SYNTHETIC_PRIVATE")).when(rows).stored(any(),any(),any(),anyLong(),any(),any());
        fail(auth(post(base()+"/"+id+"/files/"+batch.path("files").get(0).path("id").asText()).param("expectedVersion","1"),owner).contentType("application/octet-stream").content("a"),500,"INTERNAL_ERROR");
        // memory异常补偿会撤销批次；JDBC事务失败保留DRAFT的恢复场景由下一用例直接注入“仅密文已落盘”模拟崩溃。
        reset(rows);assertThat(result(auth(get(base()+"/"+id),owner),200).path("state").asText()).isEqualTo("REVOKED");
        var clean=create(new byte[]{'a'});var scope=new WorkspaceImportScope(TENANT,owner,agent);var domain=rows.find(scope,UUID.fromString(clean.path("id").asText())).orElseThrow();
        new FileSystemWorkspaceImportStorage(temp.resolve("imports"),KEY).write(domain,domain.files().getFirst(),new java.io.ByteArrayInputStream(new byte[]{'a'}));
        assertThat(complete(clean).path("state").asText()).isEqualTo("READY");
    }
    @Test void 私有派生会话运行消息审批和技能记录全入口统一隔离且分页不留空洞()throws Exception{
        var now=Instant.now();var privateConversation=new Conversation(UUID.randomUUID(),TENANT,agent,"PRIVATE_TITLE",owner,now,now);
        conversations.save(TENANT,privateConversation);selections.saveConversation(binding(privateConversation.id(),now),0);
        var privateRun=new RunRecord(UUID.randomUUID(),TENANT,agent,owner,RunKind.NORMAL,RunStatus.WAITING_APPROVAL,"PRIVATE_INPUT","PRIVATE_OUTPUT","",now,null);
        platform.save(TENANT,privateRun);selections.saveRun(binding(privateRun.id(),now));
        messages.append(TENANT,new ConversationMessageDraft(UUID.randomUUID(),TENANT,privateConversation.id(),MessageRole.USER,"合成",List.of(MessageContentBlock.text("PRIVATE_MESSAGE")),privateRun.id(),now));
        var publicConversation=new Conversation(UUID.randomUUID(),TENANT,agent,"共享标题","another",now.minusSeconds(60),now.minusSeconds(60));conversations.save(TENANT,publicConversation);
        var publicRun=new RunRecord(UUID.randomUUID(),TENANT,agent,"another",RunKind.NORMAL,RunStatus.SUCCEEDED,"共享输入","共享输出","",now.minusSeconds(60),now.minusSeconds(59));platform.save(TENANT,publicRun);
        String other="other-"+UUID.randomUUID(),conversationBase="/api/agents/"+agent+"/conversations/"+privateConversation.id();
        for(String suffix:List.of("","/messages","/active-run","/approvals","/approvals/history","/approvals/"+UUID.randomUUID(),"/workspace","/runs/"+privateRun.id()+"/workspace"))
            result(auth(get(conversationBase+suffix),other),404);
        var hidden=result(auth(get(conversationBase),other),404);
        var absent=result(auth(get("/api/agents/"+agent+"/conversations/"+UUID.randomUUID()),other),404);
        assertThat(hidden.path("code")).isEqualTo(absent.path("code"));assertThat(hidden.path("message")).isEqualTo(absent.path("message"));
        for(String suffix:List.of("","/skill-loads"))result(auth(get("/api/agents/"+agent+"/runs/"+privateRun.id()+suffix),other),404);
        var hiddenRun=result(auth(get("/api/agents/"+agent+"/runs/"+privateRun.id()),other),404);
        var absentRun=result(auth(get("/api/agents/"+agent+"/runs/"+UUID.randomUUID()),other),404);
        assertThat(hiddenRun.path("code")).isEqualTo(absentRun.path("code"));assertThat(hiddenRun.path("message")).isEqualTo(absentRun.path("message"));
        var page=result(auth(get("/api/agents/"+agent+"/conversations").param("limit","1"),other),200);
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(publicConversation.id().toString());assertThat(page.toString()).doesNotContain("PRIVATE");
        var runPage=result(auth(get("/api/agents/"+agent+"/runs").param("limit","1"),other),200);
        assertThat(runPage.path("items").get(0).path("id").asText()).isEqualTo(publicRun.id().toString());
        result(auth(get(conversationBase),owner),200);result(auth(get("/api/agents/"+agent+"/runs/"+privateRun.id()),owner),200);
        assertThatThrownBy(()->approvals.listPending(new PrincipalRef(TENANT,other,"合成",Set.of("agent:read","agent:run")),agent,privateConversation.id())).isInstanceOf(WorkspaceContextException.class);
    }
    private WorkspaceBinding binding(UUID id,Instant now){return new WorkspaceBinding(TENANT,agent,id,new WorkspaceSelection(WorkspaceSelection.Mode.IMPORTED,UUID.randomUUID()),"a".repeat(64),"合成私有导入","",1,owner,now,now);}
    /** 仅声明消费工作区能力以测试启动/HTTP合同；意外进入模型即失败，不能冒充真实Provider验收。 */
    @TestConfiguration static class RuntimeConfig {
        /** @return 不允许实际执行的合成Runtime */
        @Bean @Primary AgentRuntime runtime(){return new AgentRuntime(){@Override public boolean supportsWorkspaceContext(){return true;}@Override public AgentRunResult run(AgentRunRequest request){throw new AssertionError("T10不应调用模型");}};}
    }
}
