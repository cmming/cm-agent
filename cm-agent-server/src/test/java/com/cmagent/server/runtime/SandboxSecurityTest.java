package com.cmagent.server.runtime;

import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.runtime.*;
import com.cmagent.server.audit.*;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.service.SandboxEndpointService;
import com.cmagent.server.store.InMemorySandboxEndpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SandboxSecurityTest {
    @Test void 旧JSON材料兼容密码长度与空格原样保留()throws Exception{
        var old=mapper.readValue("{\"privateKey\":\"测试\",\"knownHosts\":\"信任\",\"caCertificate\":\"\",\"clientCertificate\":\"\"}",SandboxCredentials.class);
        assertThat(old.password()).isEmpty();
        // 按旧四字段v1格式构造历史密文，验证实际解密入口而不只验证Jackson缺省字段。
        var legacy=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");byte[] iv=new byte[12];new java.security.SecureRandom().nextBytes(iv);
        legacy.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(properties.getSandbox().getEncryptionKey()),"AES"),new javax.crypto.spec.GCMParameterSpec(128,iv));
        legacy.updateAAD(("sandbox:v1:"+tenant+":"+id+":1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] plain=mapper.writeValueAsBytes(Map.of("privateKey","测试","knownHosts","信任","caCertificate","","clientCertificate",""));
        String encrypted="v1."+Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(legacy.doFinal(plain));
        assertThat(new SandboxCredentialCipher(properties,mapper).decrypt(tenant,id,1,encrypted)).isEqualTo(old);
        assertThat(new SandboxCredentials("","","","","a".repeat(4096)).password()).hasSize(4096);
        var password=new SandboxCredentials("","信任","",""," 空格与Unicode测试 ");
        assertThat(mapper.readValue(mapper.writeValueAsBytes(password),SandboxCredentials.class)).isEqualTo(password);
        assertThat(password.password()).startsWith(" ").endsWith(" ");
        assertThat(password.toString()).doesNotContain(password.password());
        for(String invalid:List.of("x\n","x\r","x\0","中".repeat(1366),"a".repeat(4097))){
            assertThatThrownBy(()->new SandboxCredentials("","","","",invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void 密码加密与类型切换要求完整材料且保持CAS事务(){
        var cipher=new SandboxCredentialCipher(properties,mapper);var repository=new InMemorySandboxEndpointRepository();
        var service=new SandboxEndpointService(repository,properties,mock(AuditAppender.class),cipher,new SandboxTargetPolicy(properties));
        var p=new PrincipalRef(tenant,"tester","测试",Set.of());
        var material=new SandboxCredentials("","测试信任","",""," "+UUID.randomUUID()+" ");
        var endpoint=service.create(p,"密码端点","docker",SandboxConnectionMode.SSH,"127.0.0.1",2222,"tester",true,material,SandboxSshAuthType.PASSWORD);
        assertThat(endpoint.encryptedCredential()).doesNotContain(material.password());
        assertThat(cipher.decrypt(tenant,endpoint.id(),1,endpoint.encryptedCredential())).isEqualTo(material);
        assertThatThrownBy(()->cipher.decrypt(UUID.randomUUID(),endpoint.id(),1,endpoint.encryptedCredential())).isInstanceOf(SkillAccessException.class);
        var retained=service.update(p,endpoint.id(),1,"只改名称","docker",SandboxConnectionMode.SSH,"127.0.0.1",2222,"tester",true,null,SandboxSshAuthType.PASSWORD);
        assertThat(retained.credentialVersion()).isEqualTo(1);
        assertThatThrownBy(()->service.update(p,endpoint.id(),2,"切换","docker",SandboxConnectionMode.SSH,"127.0.0.1",2222,"tester",true,null,SandboxSshAuthType.KEY)).isInstanceOf(SkillAccessException.class);
        var changed=service.update(p,endpoint.id(),2,"切换","docker",SandboxConnectionMode.SSH,"127.0.0.1",2222,"tester",true,credentials,SandboxSshAuthType.KEY);
        assertThat(changed.sshAuthType()).isEqualTo(SandboxSshAuthType.KEY);assertThat(changed.credentialVersion()).isEqualTo(2);assertThat(changed.probeRevision()).isZero();
        assertThatThrownBy(()->service.create(p,"错误组合","docker",SandboxConnectionMode.TLS,"127.0.0.1",2222,"",true,material,SandboxSshAuthType.PASSWORD)).isInstanceOf(SkillAccessException.class);
    }
    @Test void 部署密码文件原样读取且缺失材料明确拒绝(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)throws Exception{
        var connection=properties.getSandbox().getConnection();connection.setMode("SSH");connection.setHost("127.0.0.1");connection.setPort(2222);
        connection.setUsername("fixture");connection.setSshAuthType(SandboxSshAuthType.PASSWORD);
        var service=new SandboxEndpointService(new InMemorySandboxEndpointRepository(),properties,mock(AuditAppender.class),new SandboxCredentialCipher(properties,mapper),new SandboxTargetPolicy(properties));
        // 这里只保存随机的测试Secret模拟部署挂载，生产路径由部署者提供而不是管理API传入。
        String password=" "+UUID.randomUUID()+" 中文 ";
        java.nio.file.Files.writeString(dir.resolve("password"),password);java.nio.file.Files.writeString(dir.resolve("known"),"测试信任");
        connection.setPasswordFile(dir.resolve("password").toString());connection.setKnownHostsFile(dir.resolve("known").toString());
        var values=service.deploymentConnection();assertThat(values.get("password")).isEqualTo(password);assertThat(values.get("sshAuthType")).isEqualTo("PASSWORD");
        connection.setPasswordFile(dir.resolve("missing").toString());
        assertThatThrownBy(service::deploymentConnection).isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_CREDENTIAL_UNAVAILABLE));
    }
    private final SkillProperties properties=new SkillProperties();
    private final ObjectMapper mapper=new ObjectMapper();
    private final UUID tenant=UUID.randomUUID(), id=UUID.randomUUID();
    private final SandboxCredentials credentials=new SandboxCredentials("-----BEGIN PRIVATE KEY-----\n测试材料\n-----END PRIVATE KEY-----","测试主机信任","","");
    @BeforeEach void prepare(){
        properties.getSandbox().setEnabled(true);
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        properties.getSandbox().setEncryptionKey(Base64.getEncoder().encodeToString(key));
        properties.getSandbox().setAllowedTargets(List.of("127.0.0.1:2222"));
    }
    @Test void 认证密文绑定租户端点版本且随机化(){
        var cipher=new SandboxCredentialCipher(properties,mapper);
        String encrypted=cipher.encrypt(tenant,id,1,credentials);
        assertThat(encrypted).doesNotContain("PRIVATE KEY","测试材料");
        assertThat(cipher.encrypt(tenant,id,1,credentials)).isNotEqualTo(encrypted);
        assertThat(cipher.decrypt(tenant,id,1,encrypted)).isEqualTo(credentials);
        for(var bad:List.of(new Object[]{UUID.randomUUID(),id,1L},new Object[]{tenant,UUID.randomUUID(),1L},new Object[]{tenant,id,2L})){
            assertThatThrownBy(()->cipher.decrypt((UUID)bad[0],(UUID)bad[1],(long)bad[2],encrypted))
                .isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_CREDENTIAL_UNAVAILABLE));
        }
        assertThat(credentials.toString()).doesNotContain("PRIVATE KEY","测试材料");
        properties.getSandbox().setEncryptionKey("");
        assertThat(cipher.ready()).isFalse();
        assertThatThrownBy(()->cipher.encrypt(tenant,id,2,credentials)).isInstanceOf(SkillAccessException.class);
    }
    @Test void 只允许部署精确目标且拒绝元数据地址与本地参数夹带(){
        var policy=new SandboxTargetPolicy(properties);
        assertThat(policy.validate(SandboxConnectionMode.SSH,"127.0.0.1",2222,"test")).isNotNull();
        assertThat(policy.validate(SandboxConnectionMode.LOCAL,"",0,"")).isNull();
        assertThatThrownBy(()->policy.validate(SandboxConnectionMode.SSH,"127.0.0.1",22,"test")).isInstanceOf(SkillAccessException.class);
        assertThatThrownBy(()->policy.validate(SandboxConnectionMode.LOCAL,"127.0.0.1",0,"")).isInstanceOf(SkillAccessException.class);
        properties.getSandbox().setAllowedTargets(List.of("169.254.169.254:22"));
        assertThatThrownBy(()->policy.validate(SandboxConnectionMode.SSH,"169.254.169.254",22,"root")).isInstanceOf(SkillAccessException.class);
    }
    @Test void 版本冲突与审计失败均不改变端点和默认选择(){
        var repo=new InMemorySandboxEndpointRepository();var audit=mock(AuditAppender.class);
        var service=new SandboxEndpointService(repo,properties,audit,new SandboxCredentialCipher(properties,mapper),new SandboxTargetPolicy(properties));
        var p=new PrincipalRef(tenant,"tester","测试",Set.of());
        var e=service.create(p,"测试","docker",SandboxConnectionMode.LOCAL,"",0,"",true,null);
        assertThat(service.list(UUID.randomUUID())).isEmpty();
        assertThatThrownBy(()->service.get(UUID.randomUUID(),e.id())).isInstanceOf(SkillAccessException.class);
        assertThatThrownBy(()->service.update(p,e.id(),9,"修改","docker",SandboxConnectionMode.LOCAL,"",0,"",true,null))
            .isInstanceOfSatisfying(SkillAccessException.class,error->assertThat(error.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_ENDPOINT_CONFLICT));
        doThrow(new AuditPersistenceException("测试审计中断",new IllegalStateException())).when(audit).append(any(),any(),any(),any(),any(),any(),any());
        assertThatThrownBy(()->service.update(p,e.id(),1,"修改","docker",SandboxConnectionMode.LOCAL,"",0,"",true,null)).isInstanceOf(AuditPersistenceException.class);
        assertThat(service.get(tenant,e.id()).revision()).isEqualTo(1);
        assertThat(service.get(tenant,e.id()).displayName()).isEqualTo("测试");
        assertThatThrownBy(()->service.create(p,"新建","docker",SandboxConnectionMode.LOCAL,"",0,"",true,null)).isInstanceOf(AuditPersistenceException.class);
        assertThat(service.list(tenant)).hasSize(1);
        assertThatThrownBy(()->service.setDefault(p,e.id(),1)).isInstanceOf(SkillAccessException.class);
        assertThat(repo.defaultId(tenant)).isEmpty();
    }
    @Test void 本地句柄幂等关闭不启动任何容器(){
        var daemon=DockerDaemonConnection.local();daemon.close();daemon.close();
    }
    @Test void 旧本地入口固定socket并清理私有客户端配置()throws Exception{
        var prefix=DockerDaemonConnection.class.getDeclaredField("prefix");prefix.setAccessible(true);
        var directory=DockerDaemonConnection.class.getDeclaredField("directory");directory.setAccessible(true);
        var daemon=DockerDaemonConnection.legacy();
        var path=(java.nio.file.Path)directory.get(daemon);
        try{
            assertThat((List<String>)prefix.get(daemon)).contains("--host");
            assertThat(java.nio.file.Files.isDirectory(path)).isTrue();
        }finally{daemon.close();}
        assertThat(java.nio.file.Files.exists(path)).isFalse();
    }
}
