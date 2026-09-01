package com.cmagent.core.runtime;

import java.util.UUID;

/**
 * 定义按租户和模型配置安全获取运行时凭据的契约。
 *
 * <p>实现方负责凭据的来源安全（数据库密文、外部 Secret Manager 等），并在无法解析时抛出
 * {@link ModelCredentialUnavailableException}；返回的 {@link ModelCredential} 仅应存活于
 * 当前一次模型调用期间，不得缓存或持久化。</p>
 */
@FunctionalInterface
public interface ModelCredentialProvider {

    /**
     * 解析指定租户和模型配置对应的运行时凭据。
     *
     * @param tenantId 当前租户标识，来自认证上下文
     * @param modelConfigId 租户内模型配置标识
     * @return 当前调用可用的模型凭据
     * @throws ModelCredentialUnavailableException 凭据缺失、无法解密或主密钥不匹配时抛出
     */
    ModelCredential resolve(UUID tenantId, UUID modelConfigId);
}
