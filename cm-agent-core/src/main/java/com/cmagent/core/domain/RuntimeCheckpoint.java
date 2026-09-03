package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 保存 AgentScope State Store 的单个加密条目。
 *
 * @param id 检查点条目标识
 * @param tenantId 由可信 Runtime userId 前缀解析的租户标识
 * @param userId AgentScope 状态槽用户标识
 * @param sessionId 使用 runId 的会话标识
 * @param stateKey AgentScope 状态键
 * @param stateType 状态类型名称，用于拒绝类型错配
 * @param listPayload 是否为状态列表
 * @param encryptedPayload AES/GCM 密文，不得记录到日志或响应
 * @param expiresAt 检查点自动失效时间
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record RuntimeCheckpoint(
        UUID id,
        UUID tenantId,
        String userId,
        String sessionId,
        String stateKey,
        String stateType,
        boolean listPayload,
        String encryptedPayload,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {
    public RuntimeCheckpoint {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()
                || stateKey == null || stateKey.isBlank() || stateType == null || stateType.isBlank()) {
            throw new IllegalArgumentException("检查点状态槽字段不能为空");
        }
        if (encryptedPayload == null || encryptedPayload.isBlank()) {
            throw new IllegalArgumentException("encryptedPayload 不能为空");
        }
        Objects.requireNonNull(expiresAt, "expiresAt 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
    }
}
