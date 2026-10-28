package com.cmagent.server.runtime;

import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 仅显式浏览器验收时在Rocky容器启动的合成夹具；真实Controller/加密存储用于原生选择上传，
 * AgentScope连接合成回环模型，不能据此声称真实Provider通过。认证令牌仅经回环HTTP传入浏览器内存。
 */
@SpringBootTest(classes=CmAgentServerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
        "server.address=127.0.0.1","server.port=18181","cm-agent.persistence.mode=memory",
        "cm-agent.fake-runtime-enabled=false","cm-agent.agentscope.enabled=true","cm-agent.agentscope.studio.enabled=false",
        "cm-agent.workspace-context.enabled=true","cm-agent.workspace-context.imports.enabled=true"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CM_AGENT_TEST_NATIVE_BROWSER",matches="true")
class WorkspaceNativeBrowserFixtureTest {
    /** 夹具独占私有卷，与仓库、生产存储和Windows候选目录隔离。 */
    private static final Path root=createRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        byte[] secret=new byte[32];new java.security.SecureRandom().nextBytes(secret);
        String key=Base64.getEncoder().encodeToString(secret);
        p.add("cm-agent.workspace-context.root-directory",()->root.resolve("reference").toString());
        p.add("cm-agent.workspace-context.imports.root-directory",()->root.resolve("imports").toString());
        p.add("cm-agent.workspace-context.imports.encryption-key",()->key);
    }
    @Autowired AgentDefinitionRepository agents;
    @Autowired ModelConfigRepository models;
    @Autowired JwtService jwt;
    @Autowired ModelCredentialCipher cipher;
    @Test void 等待明确浏览器验收结束标记()throws Exception{
        UUID tenant=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var model=models.save(new ModelConfig(UUID.randomUUID(),tenant,ModelProviderType.OPENAI_COMPATIBLE,"合成页面模型","http://127.0.0.1:18182/v1","fixture",true),cipher.encrypt("invalid-loopback-key"));
        var agent=agents.save(new AgentDefinition(UUID.randomUUID(),tenant,"本机目录验收 Agent","仅合成资料","页面验收",model.id(),"fixture",0,3,true,List.of(),"native-tester","native-tester"));
        var permissions=List.of("agent:read","agent:run","agent:approve","model-config:read","tool:read","audit:read","skill:read");
        String token=jwt.createToken(tenant,"native-tester","本机目录验收主体",permissions);
        // 端点仅绑定回环地址并由本例持有；不写令牌到磁盘/日志，不增加任何生产免认证路由。
        var bridge=HttpServer.create(new InetSocketAddress("127.0.0.1",18182),0);
        bridge.createContext("/v1/chat/completions",exchange->{
            try(var body=exchange.getRequestBody()){body.readAllBytes();}
            byte[] bytes=("data: {\"id\":\"native\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"合成资料任务完成\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=UTF-8");exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        bridge.createContext("/session",exchange->{
            byte[] bytes=("{\"accessToken\":\""+token+"\",\"agentId\":\""+agent.id()+"\",\"user\":{\"tenantId\":\""+tenant+"\",\"principalId\":\"native-tester\",\"displayName\":\"本机目录验收主体\",\"permissions\":[\""+String.join("\",\"",permissions)+"\"]}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });bridge.start();
        Files.writeString(Path.of("target/native-browser-ready"),agent.id().toString());
        try{
            long deadline=System.nanoTime()+java.time.Duration.ofMinutes(25).toNanos();
            while(!Files.exists(Path.of("target/native-browser-stop"))&&System.nanoTime()<deadline)Thread.sleep(500);
        }finally{bridge.stop(0);}
    }
    private static Path createRoot(){try{return Files.createTempDirectory(Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent().getParent(),"cm-agent-native-browser-");}
        catch(Exception failure){throw new IllegalStateException("无法创建合成浏览器私有根");}}
}
