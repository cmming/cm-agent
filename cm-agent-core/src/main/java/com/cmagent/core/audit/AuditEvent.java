package com.cmagent.core.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * 记录租户内安全或业务操作的主体、动作、资源、结果和发生时间。
 *
 * <p>审计事件一经写入就不可修改；消息与资源标识进对象前必须已脱敏，原始请求体、
 * 堆栈、凭据和外部响应不得作为事件内容。</p>
 *
 * @param id 事件唯一标识
 * @param tenantId 事件归属租户，读取边界以此隔离
 * @param principalId 执行操作的主体标识
 * @param eventType 稳定的操作类型编码，用于审计检索与分类
 * @param resourceType 操作涉及的资源类型编码
 * @param resourceId 操作涉及的资源标识
 * @param status 操作结果状态（成功、失败或拒绝）
 * @param message 已脱敏的可读操作说明
 * @param createdAt 事件发生时间，复合游标的第一排序键
 */
public record AuditEvent(
        UUID id,
        UUID tenantId,
        String principalId,
        String eventType,
        String resourceType,
        String resourceId,
        String status,
        String message,
        Instant createdAt
) {
}
