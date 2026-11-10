package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 已配对设备项目的会话或 Run 不可变来源。
 *
 * @param tenantId 可信租户
 * @param ownerId 精确原主体
 * @param projectId 固定设备项目
 * @param agentId 固定智能体
 * @param conversationId 固定会话
 * @param runId 当前 Run；会话绑定时为空
 * @param deviceId 固定执行设备
 * @param clientRootId 客户端不透明根标识
 * @param rootIdentity 固定目录对象身份摘要
 * @param projectVersion 绑定时的项目版本
 * @param createdAt 来源固定时间
 */
public record ClientProjectBinding(UUID tenantId, String ownerId, UUID projectId, UUID agentId,
        UUID conversationId, UUID runId, UUID deviceId, UUID clientRootId, String rootIdentity,
        long projectVersion, Instant createdAt) implements ProjectBinding {
    /** 已配对设备项目来源协议，旧 Server 必须拒绝而不是回退协议 0/3/4。 */
    public static final int PROTOCOL = 5;

    public ClientProjectBinding {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(agentId);
        Objects.requireNonNull(conversationId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(clientRootId);
        Objects.requireNonNull(createdAt);
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 200
                || rootIdentity == null || !rootIdentity.matches("[0-9a-f]{64}")
                || projectVersion < 1) {
            throw new IllegalArgumentException("客户端项目绑定元数据不合法");
        }
    }

    @Override public int protocol() { return PROTOCOL; }
}
