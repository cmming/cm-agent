package com.cmagent.core.runtime;

import java.util.UUID;

@FunctionalInterface
/**
 * 定义按租户和模型配置安全获取运行时凭据的契约。
 */
public interface ModelCredentialProvider {

    /**
     * 解析指定租户和模型配置对应的运行时凭据。
      *
      * @param tenantId 当前租户标识
      * @param modelConfigId 模型配置标识
     */
    ModelCredential resolve(UUID tenantId, UUID modelConfigId);
}
