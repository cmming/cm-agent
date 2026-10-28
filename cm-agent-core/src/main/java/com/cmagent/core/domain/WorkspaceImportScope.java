package com.cmagent.core.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 已认证的导入归属；必须由 JwtSession 与服务端核实的 Agent 构造，不能使用请求体中的身份。
 * @param tenantId 可信租户
 * @param principalId 原上传主体，普通租户管理员不自动获得访问权
 * @param agentId 归属 Agent
 */
public record WorkspaceImportScope(UUID tenantId, String principalId, UUID agentId) {
    public WorkspaceImportScope {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(agentId);
        if (principalId == null || principalId.isBlank() || principalId.length() > 200
                || principalId.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("导入主体不合法");
    }
}
