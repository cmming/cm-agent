package com.cmagent.server.security;

import java.util.List;

public record LoginResponse(
        String tenantId,
        String principalId,
        String displayName,
        List<String> permissions,
        String accessToken
) {
}
