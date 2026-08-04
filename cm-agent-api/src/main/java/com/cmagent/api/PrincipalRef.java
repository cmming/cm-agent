package com.cmagent.api;

import java.util.Set;
import java.util.UUID;

/**
 * 已认证调用主体的最小安全上下文。
 *
 * @param tenantId 主体所属租户，后续数据访问必须使用该值进行隔离
 * @param principalId 租户内唯一的主体标识
 * @param displayName 用于界面和审计展示的名称
 * @param permissions 主体当前拥有的权限快照
 */
public record PrincipalRef(UUID tenantId, String principalId, String displayName, Set<String> permissions) {

    /**
     * 冻结权限集合，避免认证完成后权限被外部修改。
      *
      * @param tenantId 当前租户标识
      * @param principalId 租户内主体标识
      * @param displayName 主体展示名称
      * @param permissions 主体权限集合
     */
    public PrincipalRef {
        permissions = Set.copyOf(permissions);
    }
}
