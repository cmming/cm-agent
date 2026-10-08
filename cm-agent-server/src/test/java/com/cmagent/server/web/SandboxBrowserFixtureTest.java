package com.cmagent.server.web;

import com.cmagent.server.CmAgentServerApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.Base64;

/**
 * 浏览器验收专用的隔离服务，仅在Rocky Maven容器中启用。
 * 使用memory和测试凭据，既不共享用户服务端口也不连接外部模型；释放文件或十分钟上限结束后自动关闭。
 */
@EnabledIfEnvironmentVariable(named="CM_AGENT_TEST_BROWSER",matches="true")
@SpringBootTest(classes=CmAgentServerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
    "server.port=18097","cm-agent.persistence.mode=memory","cm-agent.skills.enabled=true",
    "cm-agent.skills.sandbox.enabled=true","cm-agent.skills.sandbox.connection.mode=LOCAL",
    "cm-agent.skills.sandbox.allowed-targets[0]=127.0.0.1:2222",
    "cm-agent.agentscope.studio.enabled=false"})
@ActiveProfiles("test")
class SandboxBrowserFixtureTest {
    @DynamicPropertySource static void key(DynamicPropertyRegistry registry){
        byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);
        String value=Base64.getEncoder().encodeToString(bytes);
        registry.add("cm-agent.skills.sandbox.encryption-key",()->value);
    }
    @Test void 浏览器验收独立服务生命周期()throws Exception{
        Path ready=Path.of("target/sandbox-browser-ready"),release=Path.of("target/sandbox-browser-release");
        Files.createDirectories(ready.getParent());Files.deleteIfExists(release);Files.writeString(ready,"就绪");
        try{
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
            while(!Files.exists(release) && System.nanoTime()<end)Thread.sleep(500);
        }finally{Files.deleteIfExists(ready);}
    }
}
