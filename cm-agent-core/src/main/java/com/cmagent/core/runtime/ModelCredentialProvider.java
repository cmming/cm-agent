package com.cmagent.core.runtime;

import java.util.UUID;

@FunctionalInterface
public interface ModelCredentialProvider {

    ModelCredential resolve(UUID tenantId, UUID modelConfigId);
}
