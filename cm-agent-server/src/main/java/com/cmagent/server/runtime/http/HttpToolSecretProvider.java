package com.cmagent.server.runtime.http;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface HttpToolSecretProvider {
    Optional<String> resolve(UUID tenantId, String secretRef);
}
