package com.cmagent.server.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/**
 * Windows JVM真实认证/Unix socket中继验收；Docker夹具与账户修改只通过ssh rocky执行。
 * 外部执行者创建项目专用临时夹具并负责删除，本测试只读其公开端口/主机公钥文件。
 * 密码随机生成且仅经chpasswd stdin提交；本机不启动Docker、不读取用户服务配置。
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named="CM_AGENT_TEST_PASSWORD_WINDOWS",matches="true")
class PasswordSshWindowsFixtureTest {
    @Test void Windows内存口令认证到Rocky临时Docker且轮换后在途连接保留()throws Exception{
        String fixture=System.getProperty("sandbox.fixture.name");
        assertThat(fixture).matches("cm-agent-password-win-[a-z0-9-]+");
        int remotePort=Integer.parseInt(System.getProperty("sandbox.fixture.port"));
        String publicKey=Files.readString(Path.of(System.getProperty("sandbox.fixture.public-key")));
        String password=" "+UUID.randomUUID()+" 中文口令 ";
        setPassword(fixture,password);
        int localPort;
        try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){localPort=socket.getLocalPort();}
        Process tunnel=new ProcessBuilder("ssh","-o","ExitOnForwardFailure=yes","-N","-L",
            "127.0.0.1:"+localPort+":127.0.0.1:"+remotePort,"rocky")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{
            boolean ready=false;
            for(int attempt=0;attempt<50 && tunnel.isAlive();attempt++){
                try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",localPort),100);ready=true;break;}
                catch(java.io.IOException retry){Thread.sleep(100);}
            }
            assertThat(ready).isTrue();
            var credentials=new SandboxCredentials("","localhost "+publicKey,"","",password);
            try(var relay=PasswordSshRelay.open("localhost",InetAddress.getByName("127.0.0.1"),localPort,"root",credentials,Duration.ofSeconds(8));
                    var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()){
                setPassword(fixture," "+UUID.randomUUID()+" 轮换口令 ");
                var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+relay.port()+"/_ping")).timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEqualTo("OK");
            }
            assertThatThrownBy(()->PasswordSshRelay.open("localhost",InetAddress.getByName("127.0.0.1"),localPort,"root",credentials,Duration.ofSeconds(8)))
                .isInstanceOfSatisfying(com.cmagent.core.runtime.SkillAccessException.class,e->assertThat(e.code()).isEqualTo(com.cmagent.api.ApiErrorCode.SKILL_SANDBOX_AUTH_FAILED));
        }finally{tunnel.destroyForcibly();assertThat(tunnel.waitFor(3,TimeUnit.SECONDS)).isTrue();}
    }
    private void setPassword(String fixture,String password)throws Exception{
        Process process=new ProcessBuilder("ssh","rocky","docker","exec","-i",fixture,"chpasswd")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try(var input=process.getOutputStream()){input.write(("root:"+password+"\n").getBytes(StandardCharsets.UTF_8));}
        assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
    }
}
