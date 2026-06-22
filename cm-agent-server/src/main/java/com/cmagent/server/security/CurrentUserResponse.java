package com.cmagent.server.security;

import java.util.List;

public record CurrentUserResponse(
        String tenantId,
        String principalId,
        String displayName,
        List<String> permissions
) {
}
