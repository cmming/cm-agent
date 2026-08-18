package com.cmagent.server.config;

import com.cmagent.server.runtime.ModelCredentialCipher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 装配模型凭据的加密服务，保证 API Key 进入仓储前已被加密。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelCredentialProperties.class)
public class ModelCredentialEncryptionConfiguration {

    /**
     * 创建 AES/GCM 密文编解码器。
     *
     * @param properties 模型凭据主密钥配置
     * @return 不保存明文状态的凭据编解码器
     */
    @Bean
    ModelCredentialCipher modelCredentialCipher(ModelCredentialProperties properties) {
        return new ModelCredentialCipher(properties.resolveSecretKey());
    }
}
