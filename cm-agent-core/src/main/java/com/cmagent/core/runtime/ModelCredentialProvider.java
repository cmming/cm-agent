package com.cmagent.core.runtime;

import java.util.UUID;

@FunctionalInterface
/**
 * ModelCredentialProvider 的核心领域类型。
 */
public interface ModelCredentialProvider {

    /**
     * 定义 resolve 操作。
     */
    ModelCredential resolve(UUID tenantId, UUID modelConfigId);
}
