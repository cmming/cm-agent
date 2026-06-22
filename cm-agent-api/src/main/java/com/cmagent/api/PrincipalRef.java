package com.cmagent.api;

import java.util.Set;
import java.util.UUID;

public record PrincipalRef(UUID tenantId, String principalId, String displayName, Set<String> permissions) {

    public PrincipalRef {
        permissions = Set.copyOf(permissions);
    }
}
