package com.cmagent.server.security;

import java.util.List;

/**
 * 当前登录主体响应，不返回令牌原文或服务端安全配置。
 */
public record CurrentUserResponse(
        String tenantId,
        String principalId,
        String displayName,
        List<String> permissions
) {
}
