package com.cmagent.server.runtime;

import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.*;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.service.SandboxEndpointService;
import com.cmagent.server.store.InMemorySandboxEndpointRepository;
import com.cmagent.server.audit.AuditAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 在Rocky的Maven21容器内建立项目专用SSH/TLS daemon，不修改宿主daemon。
 * 仅可信测试daemon夹具需要privileged运行嵌套容器；技能容器仍无宿主挂载、无网络、非root且受限额约束。
 * 所有密钥临时生成、只在测试私有目录中使用，不读取运行中服务的认证或配置。
 */
@EnabledIfEnvironmentVariable(named="CM_AGENT_TEST_REMOTE_SANDBOX",matches="true")
class RemoteDockerSandboxIntegrationTest {
    @Test void 密码SSH真实执行轮换固定连接认证拒绝与同主机清理()throws Exception{
        Path dir=Files.createTempDirectory("sandbox-password-verification-");
        String password=" "+UUID.randomUUID()+" 测试口令 ";
        try{
            run(dir,"docker","save","-o","python.tar","python:3.12-alpine");
            run(dir,"ssh-keygen","-q","-t","ed25519","-N","","-f","host");
            Files.writeString(dir.resolve("sshd_config"),"Port 22\nListenAddress 0.0.0.0\nHostKey /fixture/host\nPermitRootLogin yes\nPasswordAuthentication yes\nKbdInteractiveAuthentication no\nAllowTcpForwarding yes\nAllowStreamLocalForwarding yes\nPidFile /tmp/sshd.pid\nMaxAuthTries 1\nLogLevel ERROR\n");
            var container=fixture(dir);String packages=System.getenv("CM_AGENT_TEST_SSH_PACKAGE_DIR");
            if(packages!=null)container.withCopyFileToContainer(MountableFile.forHostPath(packages),"/fixture/apk");
            String install=packages==null?"apk add --no-cache openssh-server":"apk add --no-network /fixture/apk/*.apk";
            try(var gateway=container.withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("host"),0600),"/fixture/host")
                    .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("sshd_config"),0600),"/fixture/sshd_config")
                    .withExposedPorts(22).waitingFor(Wait.forLogMessage(".*password fixture ready.*",1).withStartupTimeout(Duration.ofMinutes(3)))
                    .withCommand(new String[]{install+" >/dev/null && mkdir -p /run/sshd; "+daemonStart("")+"/usr/sbin/sshd -f /fixture/sshd_config; echo 'password fixture ready'; wait"})){
                gateway.start();setPassword(gateway,password);
                String host=gateway.getHost();int port=gateway.getMappedPort(22);
                String known=host+" "+Files.readString(dir.resolve("host.pub"));
                var credentials=new SandboxCredentials("",known,"","",password);
                exercise(SandboxConnectionMode.SSH,host,port,"root",credentials,gateway);
                // 密码仅经stdin送到一次性夹具chpasswd，不进入进程参数、环境或文件。
                String changed=" "+UUID.randomUUID()+" 轮换口令 ";
                try(var pinned=DockerDaemonConnection.remote(SandboxConnectionMode.SSH,host,java.net.InetAddress.getByName(host),port,"root",credentials,Duration.ofSeconds(15),SandboxSshAuthType.PASSWORD)){
                    setPassword(gateway,changed);
                    Process info=pinned.start(List.of("info","--format","{{.OSType}}"));
                    assertThat(new String(info.getInputStream().readAllBytes())).contains("linux");assertThat(info.waitFor()).isZero();
                }
                assertThatThrownBy(()->DockerDaemonConnection.remote(SandboxConnectionMode.SSH,host,java.net.InetAddress.getByName(host),port,"root",credentials,Duration.ofSeconds(15),SandboxSshAuthType.PASSWORD))
                    .isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_AUTH_FAILED));
                run(dir,"ssh-keygen","-q","-t","ed25519","-N","","-f","wrong");
                var badHost=new SandboxCredentials("",host+" "+Files.readString(dir.resolve("wrong.pub")),"","",changed);
                assertThatThrownBy(()->DockerDaemonConnection.remote(SandboxConnectionMode.SSH,host,java.net.InetAddress.getByName(host),port,"root",badHost,Duration.ofSeconds(15),SandboxSshAuthType.PASSWORD))
                    .isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_AUTH_FAILED));
                var latest=new SandboxCredentials("",known,"","",changed);
                reconfigurePasswordGateway(gateway,"PasswordAuthentication yes","PasswordAuthentication no");
                assertThatThrownBy(()->DockerDaemonConnection.remote(SandboxConnectionMode.SSH,host,java.net.InetAddress.getByName(host),port,"root",latest,Duration.ofSeconds(8),SandboxSshAuthType.PASSWORD))
                    .isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_AUTH_FAILED));
                reconfigurePasswordGateway(gateway,"PasswordAuthentication no","PasswordAuthentication yes");
                reconfigurePasswordGateway(gateway,"AllowStreamLocalForwarding yes","AllowStreamLocalForwarding no");
                assertThatThrownBy(()->DockerDaemonConnection.remote(SandboxConnectionMode.SSH,host,java.net.InetAddress.getByName(host),port,"root",latest,Duration.ofSeconds(8),SandboxSshAuthType.PASSWORD))
                    .isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE));
                reconfigurePasswordGateway(gateway,"AllowStreamLocalForwarding no","AllowStreamLocalForwarding yes");
                disconnected(SandboxConnectionMode.SSH,host,port,"root",latest,gateway);
            }
        }finally{erase(dir);}
    }
    private void reconfigurePasswordGateway(GenericContainer<?> gateway,String oldValue,String newValue)throws Exception{
        // 仅修改本测试创建的一次性sshd，验证服务端禁用认证/转发；不触碰Rocky宿主服务。
        var result=gateway.execInContainer("sh","-c","sed -i 's/"+oldValue+"/"+newValue+"/' /fixture/sshd_config && kill -HUP $(cat /tmp/sshd.pid)");
        assertThat(result.getExitCode()).isZero();Thread.sleep(150);
    }
    private void setPassword(GenericContainer<?> gateway,String password)throws Exception{
        Process process=new ProcessBuilder("docker","exec","-i",gateway.getContainerId(),"chpasswd")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try(var input=process.getOutputStream()){input.write(("root:"+password+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        assertThat(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
    }
    @Test void SSH端点真实探测执行主机信任失败及超时清理()throws Exception{
        Path dir=Files.createTempDirectory("sandbox-ssh-verification-");
        try{
            run(dir,"docker","save","-o","python.tar","python:3.12-alpine");
            run(dir,"ssh-keygen","-q","-t","ed25519","-N","","-f","host");
            run(dir,"ssh-keygen","-q","-t","ed25519","-N","","-f","identity");
            String config="Port 22\nListenAddress 0.0.0.0\nHostKey /fixture/host\nAuthorizedKeysFile /root/.ssh/authorized_keys\nPermitRootLogin prohibit-password\nPasswordAuthentication no\nKbdInteractiveAuthentication no\nAllowTcpForwarding yes\nAllowStreamLocalForwarding yes\nStrictModes yes\nPidFile /tmp/sshd.pid\n";
            Files.writeString(dir.resolve("sshd_config"),config);
            String packageDir=System.getenv("CM_AGENT_TEST_SSH_PACKAGE_DIR");
            var sshFixture=fixture(dir);
            // 时钟或网络受限环境可提供经可信HTTPS下载的官方APK；安装仍校验镜像内Alpine签名密钥。
            if(packageDir!=null)sshFixture.withCopyFileToContainer(MountableFile.forHostPath(packageDir),"/fixture/apk");
            String install=packageDir==null?"apk add --no-cache openssh-server":"apk add --no-network /fixture/apk/*.apk";
            try(var gateway=sshFixture
                .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("host"),0600),"/fixture/host")
                .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("identity.pub"),0600),"/fixture/identity.pub")
                .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("sshd_config"),0600),"/fixture/sshd_config")
                // Testcontainers 1.21.0的withCommand(String)按空格切分，传String[]保证sh -c收到完整脚本。
                // 必须等最终sshd明确就绪再验证认证。
                .withExposedPorts(22).waitingFor(Wait.forLogMessage(".*Server listening on.*",1).withStartupTimeout(Duration.ofMinutes(3)))
                // x不是可用密码，仅解锁测试账户；密码认证仍明确关闭。
                .withCommand(new String[]{install+" >/dev/null || exit 1; sed -i 's/^root:[^:]*:/root:x:/' /etc/shadow && mkdir -p /run/sshd /root/.ssh && chmod 700 /root/.ssh && cp /fixture/identity.pub /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys && "+daemonStart("")+"exec /usr/sbin/sshd -D -e -f /fixture/sshd_config"})){
                gateway.start();
                String host=gateway.getHost();int port=gateway.getMappedPort(22);
                // HostKeyAlias固定原host；使用精确条目而非通配符，验证实际配置匹配。
                var credentials=new SandboxCredentials(Files.readString(dir.resolve("identity")),host+" "+Files.readString(dir.resolve("host.pub")),"","");
                try{exercise(SandboxConnectionMode.SSH,host,port,"root",credentials,gateway);}
                catch(SkillAccessException failure){
                    // 仅留测试sshd的认证状态诊断，不包含临时私钥或生产服务数据。
                    Files.writeString(Path.of("target/sandbox-ssh-fixture-diagnostic.log"),gateway.getLogs());
                    throw failure;
                }
                // 相同地址但错误主机信任必须失败，禁止关闭校验或回退本地。
                run(dir,"ssh-keygen","-q","-t","ed25519","-N","","-f","wrong");
                var wrong=new SandboxCredentials(credentials.privateKey(),"* "+Files.readString(dir.resolve("wrong.pub")),"","");
                assertThatThrownBy(()->open(SandboxConnectionMode.SSH,host,port,"root",wrong)).isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_AUTH_FAILED));
                disconnected(SandboxConnectionMode.SSH,host,port,"root",credentials,gateway);
            }
        }finally{erase(dir);}
    }
    @Test void 双向TLS端点真实探测执行错误CA与服务端身份拒绝()throws Exception{
        Path dir=Files.createTempDirectory("sandbox-tls-verification-");
        try{
            run(dir,"docker","save","-o","python.tar","python:3.12-alpine");
            run(dir,"openssl","req","-x509","-newkey","rsa:2048","-nodes","-keyout","ca.key","-out","ca.pem","-days","1","-subj","/CN=CM-Agent-Test-CA");
            run(dir,"openssl","req","-newkey","rsa:2048","-nodes","-keyout","server.key","-out","server.csr","-subj","/CN=localhost");
            // 容器测试访问地址来自Testcontainers可信夹具，不是业务模型输入。
            String dockerHost=org.testcontainers.DockerClientFactory.instance().dockerHostIpAddress();
            Files.writeString(dir.resolve("server.ext"),"subjectAltName=DNS:localhost,IP:127.0.0.1,IP:"+dockerHost+"\nextendedKeyUsage=serverAuth\n");
            run(dir,"openssl","x509","-req","-in","server.csr","-CA","ca.pem","-CAkey","ca.key","-CAcreateserial","-out","server.pem","-days","1","-extfile","server.ext");
            run(dir,"openssl","req","-newkey","rsa:2048","-nodes","-keyout","client.key","-out","client.csr","-subj","/CN=CM-Agent-Test-Client");
            Files.writeString(dir.resolve("client.ext"),"extendedKeyUsage=clientAuth\n");
            run(dir,"openssl","x509","-req","-in","client.csr","-CA","ca.pem","-CAkey","ca.key","-CAcreateserial","-out","client.pem","-days","1","-extfile","client.ext");
            try(var gateway=fixture(dir)
                .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("server.pem")),"/fixture/server.pem")
                .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("server.key"),0600),"/fixture/server.key")
                .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("ca.pem")),"/fixture/ca.pem")
                .withExposedPorts(2376).waitingFor(Wait.forLogMessage(".*TLS fixture ready.*",1).withStartupTimeout(Duration.ofMinutes(3)))
                .withCommand(new String[]{daemonStart("--tlsverify --tlscacert=/fixture/ca.pem --tlscert=/fixture/server.pem --tlskey=/fixture/server.key --host=tcp://0.0.0.0:2376")+"echo 'TLS fixture ready'; wait"})){
                gateway.start();String host=gateway.getHost();int port=gateway.getMappedPort(2376);
                var credentials=new SandboxCredentials(Files.readString(dir.resolve("client.key")),"",Files.readString(dir.resolve("ca.pem")),Files.readString(dir.resolve("client.pem")));
                try{exercise(SandboxConnectionMode.TLS,host,port,"",credentials,gateway);}
                catch(SkillAccessException failure){Files.writeString(Path.of("target/sandbox-tls-fixture-diagnostic.log"),gateway.getLogs()+gateway.execInContainer("sh","-c","tail -n 15 /tmp/daemon.log 2>&1").getStdout());throw failure;}
                assertThatThrownBy(()->DockerDaemonConnection.remote(SandboxConnectionMode.TLS,"wrong.example.test",java.net.InetAddress.getByName(host),port,"",credentials))
                    .isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_AUTH_FAILED));
                run(dir,"openssl","req","-x509","-newkey","rsa:2048","-nodes","-keyout","wrong.key","-out","wrong.pem","-days","1","-subj","/CN=Wrong-Test-CA");
                var wrong=new SandboxCredentials(credentials.privateKey(),"",Files.readString(dir.resolve("wrong.pem")),credentials.clientCertificate());
                assertThatThrownBy(()->open(SandboxConnectionMode.TLS,host,port,"",wrong)).isInstanceOf(SkillAccessException.class);
                run(dir,"openssl","x509","-req","-in","client.csr","-CA","ca.pem","-CAkey","ca.key","-CAcreateserial","-out","expired.pem","-days","0","-extfile","client.ext");
                var expired=new SandboxCredentials(credentials.privateKey(),"",credentials.caCertificate(),Files.readString(dir.resolve("expired.pem")));
                // 当前过期证书必须被真实daemon拒绝，不能只在mock验证输入。
                Thread.sleep(1100);
                assertThatThrownBy(()->exercise(SandboxConnectionMode.TLS,host,port,"",expired,gateway)).isInstanceOf(SkillAccessException.class);
                disconnected(SandboxConnectionMode.TLS,host,port,"",credentials,gateway);
            }
        }finally{erase(dir);}
    }
    private DockerDaemonConnection open(SandboxConnectionMode mode,String host,int port,String user,SandboxCredentials c)throws Exception{
        return DockerDaemonConnection.remote(mode,host,java.net.InetAddress.getByName(host),port,user,c);
    }
    private void exercise(SandboxConnectionMode mode,String host,int port,String user,SandboxCredentials credentials,GenericContainer<?> gateway)throws Exception{
        var p=new SkillProperties();p.getSandbox().setEnabled(true);p.getSandbox().setAllowedTargets(List.of(host+":"+port));
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);p.getSandbox().setEncryptionKey(Base64.getEncoder().encodeToString(key));
        var mapper=new ObjectMapper();var repo=new InMemorySandboxEndpointRepository();var audit=mock(AuditAppender.class);
        var service=new SandboxEndpointService(repo,p,audit,new SandboxCredentialCipher(p,mapper),new SandboxTargetPolicy(p));
        var principal=new PrincipalRef(UUID.randomUUID(),"verification","远程验证",Set.of());
        var auth=credentials.password().isEmpty()?SandboxSshAuthType.KEY:SandboxSshAuthType.PASSWORD;
        var endpoint=service.create(principal,"临时远程端点","docker",mode,host,port,user,true,credentials,auth);
        endpoint=service.probe(principal,endpoint.id(),1);service.setDefault(principal,endpoint.id(),1);
        assertThat(endpoint.probeStatus()).isEqualTo("PASSED");
        var request=new SkillReadRequest(principal,UUID.randomUUID(),UUID.randomUUID(),"fixture",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"main.py");
        var docker=new DockerSkillSandbox(p.getSandbox());var managed=new ManagedSkillSandbox(List.of(docker),p,service,audit,mapper);
        String script="import os,pathlib,socket\nassert os.getuid()==65534\nassert not pathlib.Path('/var/run/docker.sock').exists()\nassert not pathlib.Path('/host').exists()\nprint('remote-isolated')";
        assertThat(managed.execute(request,Map.of("main.py",script),"")).isEqualTo("remote-isolated\n");
        // 凭据轮换后探测失效；旧调用已打开的连接不切换，后续调用必须等新版本通过。
        try(var pinned=managed.open(request,Map.of())){
            var updated=service.update(principal,endpoint.id(),1,"轮换后的临时端点","docker",mode,host,port,user,true,credentials,auth);
            assertThat(updated.credentialVersion()).isEqualTo(2);
            assertThat(pinned.execute(Map.of("main.py","print('pinned')"),"")).isEqualTo("pinned\n");
            assertThatThrownBy(()->managed.open(request,Map.of())).isInstanceOf(SkillAccessException.class);
        }
        service.probe(principal,endpoint.id(),2);
        p.getSandbox().setMaxOutputBytes(1024);
        assertThatThrownBy(()->managed.execute(request,Map.of("main.py","while True: print('x'*4096,flush=True)"),""))
            .isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_LIMIT_EXCEEDED));
        p.getSandbox().setMaxOutputBytes(32768);
        p.getSandbox().setTimeout(Duration.ofSeconds(2));
        assertThatThrownBy(()->managed.execute(request,Map.of("main.py","import time\ntime.sleep(120)"),""))
            .isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_TIMEOUT));
        var remaining=gateway.execInContainer("docker","-H","unix:///var/run/docker.sock","ps","-aq","--filter","name=cm-agent-skill-");
        assertThat(remaining.getExitCode()).isZero();assertThat(remaining.getStdout()).isBlank();
        var interrupted=new java.util.concurrent.atomic.AtomicReference<SkillAccessException>();
        Thread running=Thread.startVirtualThread(()->{
            try{managed.execute(request,Map.of("main.py","import time\ntime.sleep(120)"),"");}
            catch(SkillAccessException failure){interrupted.set(failure);}
        });
        try{
            boolean started=false;
            for(int attempt=0;attempt<15 && running.isAlive();attempt++){
                if(!gateway.execInContainer("docker","ps","-q","--filter","name=cm-agent-skill-").getStdout().isBlank()){started=true;break;}
                Thread.sleep(50);
            }
            assertThat(started).isTrue();
        }finally{running.interrupt();running.join(10000);}
        assertThat(running.isAlive()).isFalse();assertThat(interrupted.get()).isNotNull();
        assertThat(interrupted.get().code()).isIn(ApiErrorCode.SKILL_SANDBOX_TIMEOUT,ApiErrorCode.SKILL_SANDBOX_CLEANUP_FAILED);
        assertThat(gateway.execInContainer("docker","ps","-aq","--filter","name=cm-agent-skill-").getStdout()).isBlank();
    }
    private GenericContainer<?> fixture(Path dir){
        // 夹具只导入部署预备镜像，不挂载宿主socket；独立daemon及所有嵌套数据随夹具删除。
        return new GenericContainer<>("docker:23-dind").withPrivilegedMode(true).withEnv("DOCKER_TLS_CERTDIR","")
            .withCopyFileToContainer(MountableFile.forHostPath(dir.resolve("python.tar")),"/fixture/python.tar")
            .withCreateContainerCmdModifier(cmd->cmd.withEntrypoint("/usr/local/bin/dind","sh","-ec"));
    }
    private String daemonStart(String tls){
        return "{ dockerd --data-root=/tmp/fixture-daemon --storage-driver=vfs --iptables=false --bridge=none --host=unix:///var/run/docker.sock "+tls+" >/tmp/daemon.log 2>&1 & } && "
            +"ready=0; for n in $(seq 1 60); do if docker -H unix:///var/run/docker.sock info >/dev/null 2>&1; then ready=1; break; fi; sleep 1; done; "
            +"test $ready = 1 || { tail -n 12 /tmp/daemon.log; exit 1; }; docker -H unix:///var/run/docker.sock load -i /fixture/python.tar >/dev/null || exit 1; ";
    }
    private void disconnected(SandboxConnectionMode mode,String host,int port,String user,SandboxCredentials c,GenericContainer<?> gateway)throws Exception{
        var p=new SkillProperties();p.getSandbox().setEnabled(true);
        var request= new SkillReadRequest(new PrincipalRef(UUID.randomUUID(),"tester","测试",Set.of()),UUID.randomUUID(),UUID.randomUUID(),"disconnect",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"main.py");
        var connection=new HashMap<String,String>(Map.of("mode",mode.name(),"host",host,"address",java.net.InetAddress.getByName(host).getHostAddress(),"port",String.valueOf(port),"username",user,"privateKey",c.privateKey(),"knownHosts",c.knownHosts(),"caCertificate",c.caCertificate(),"clientCertificate",c.clientCertificate()));
        connection.put("password",c.password());connection.put("sshAuthType",c.password().isEmpty()?"KEY":"PASSWORD");
        try(var execution=new DockerSkillSandbox(p.getSandbox()).open(request,connection)){
            gateway.stop();
            assertThatThrownBy(()->execution.execute(Map.of("main.py","print('never-delivered')"),""))
                .isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isIn(ApiErrorCode.SKILL_SANDBOX_CLEANUP_FAILED,ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE,ApiErrorCode.SKILL_SANDBOX_TIMEOUT,ApiErrorCode.SKILL_SANDBOX_AUTH_FAILED));
        }
    }
    private void run(Path dir,String... command)throws Exception{
        var process=new ProcessBuilder(command).directory(dir.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("临时认证夹具生成超时");}
        if(process.exitValue()!=0)throw new IllegalStateException("临时认证夹具生成失败");
    }
    private void erase(Path dir)throws Exception{
        try(var files=Files.walk(dir)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}
    }
}
