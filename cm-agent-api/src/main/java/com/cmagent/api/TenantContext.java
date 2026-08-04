package com.cmagent.api;

import java.util.UUID;

/**
 * 在不依赖 Web 或安全框架的模块间传递租户边界。
 *
 * @param tenantId 当前操作所属的租户标识
 */
public record TenantContext(UUID tenantId) {
}
