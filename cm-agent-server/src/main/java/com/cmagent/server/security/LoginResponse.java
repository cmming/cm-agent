package com.cmagent.server.security;

import java.util.List;

/**
 * 登录成功响应，返回短期访问令牌及当前主体的非敏感信息。
 */
public record LoginResponse(
        String tenantId,
        String principalId,
        String displayName,
        List<String> permissions,
        String accessToken
) {
}
