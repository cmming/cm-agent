package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 会话或Run固定的本机项目来源；缺失不能回退旧参考资料。
 * @param tenantId 可信租户
 * @param ownerId 原创建者，必须与关联会话及运行发起者精确一致
 * @param projectId 项目标识
 * @param agentId 固定Agent
 * @param conversationId 固定会话
 * @param runId Run绑定时非空，会话绑定时为空
 * @param rootBindingId 固定根登记，不随当前显示名变化
 * @param rootIdentity 固定根身份SHA256
 * @param nodeId 固定本机执行节点
 * @param projectVersion 绑定时的项目元数据版本
 * @param createdAt 来源固定时间
 */
public record LocalProjectBinding(UUID tenantId, String ownerId, UUID projectId, UUID agentId,
        UUID conversationId, UUID runId, UUID rootBindingId, String rootIdentity, String nodeId,
        long projectVersion, Instant createdAt) {
    /** 项目专用来源见证；旧来源0和导入3保持原语义，旧实例必须拒绝不认识的4。 */
    public static final int PROTOCOL = 4;

    public LocalProjectBinding {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(projectId); Objects.requireNonNull(agentId);
        Objects.requireNonNull(conversationId); Objects.requireNonNull(rootBindingId); Objects.requireNonNull(createdAt);
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 200
                || nodeId == null || nodeId.isBlank() || nodeId.length() > 200
                || rootIdentity == null || !rootIdentity.matches("[0-9a-f]{64}") || projectVersion < 1)
            throw new IllegalArgumentException("项目绑定元数据不合法");
    }
}

