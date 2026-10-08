package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.server.config.SkillProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** 独立 AES/GCM 主密钥和 AAD 绑定租户/端点/凭据版本，拒绝密文跨资源复制。 */
@Component
public class SandboxCredentialCipher {
    /** 主密钥仅来自部署配置；默认关闭时允许未配置，管理启用时拒绝缺失。 */
    private final SkillProperties properties;
    /** 序列化只用于加解密，不记录材料。 */
    private final ObjectMapper mapper;
    /** @param properties 可信部署配置 @param mapper JSON 转换器 */
    public SandboxCredentialCipher(SkillProperties properties, ObjectMapper mapper) { this.properties=properties; this.mapper=mapper; }
    /** @return 部署是否配置了合法主密钥，不输出内容 */
    public boolean ready() { try { key(); return true; } catch (SkillAccessException failure) { return false; } }
    private SecretKey key() {
        try {
            byte[] bytes=Base64.getDecoder().decode(properties.getSandbox().getEncryptionKey());
            // SecretKeySpec复制输入；立即擦除解码缓冲，避免额外的主密钥副本留在堆中。
            try {
                if(bytes.length!=32) throw new IllegalArgumentException();
                return new SecretKeySpec(bytes,"AES");
            } finally { Arrays.fill(bytes,(byte)0); }
        } catch(RuntimeException failure) { throw unavailable(); }
    }
    /** @param tenant 租户 @param id 端点 @param version 材料版本 @param value 待加密材料 @return 版本密文 */
    public String encrypt(UUID tenant, UUID id, long version, SandboxCredentials value) {
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv=new byte[12]; new SecureRandom().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            cipher.updateAAD(aad(tenant,id,version));
            byte[] plain=mapper.writeValueAsBytes(value);
            try { return "v1."+Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(cipher.doFinal(plain)); }
            finally { Arrays.fill(plain,(byte)0); }
        } catch(SkillAccessException failure) { throw failure; }
        catch(Exception failure) { throw unavailable(); }
    }
    /** @param tenant 租户 @param id 端点 @param version 材料版本 @param value 已保存密文 @return 本次调用材料 */
    public SandboxCredentials decrypt(UUID tenant, UUID id, long version, String value) {
        try {
            String[] parts=value.split("\\.",-1); if(parts.length!=3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[1])));
            cipher.updateAAD(aad(tenant,id,version));
            byte[] plain=cipher.doFinal(Base64.getDecoder().decode(parts[2]));
            try { return mapper.readValue(plain,SandboxCredentials.class); } finally { Arrays.fill(plain,(byte)0); }
        } catch(Exception failure) { throw unavailable(); }
    }
    private static byte[] aad(UUID tenant,UUID id,long version) { return ("sandbox:v1:"+tenant+":"+id+":"+version).getBytes(StandardCharsets.UTF_8); }
    private static SkillAccessException unavailable() {
        return new SkillAccessException(ApiErrorCode.SKILL_SANDBOX_CREDENTIAL_UNAVAILABLE,"沙箱凭据不可用，请核对部署主密钥和凭据配置",UUID.randomUUID().toString(),true);
    }
}
