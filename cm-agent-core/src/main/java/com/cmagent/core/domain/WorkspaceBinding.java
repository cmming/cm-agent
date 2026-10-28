package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 会话或运行的工作区选择元数据，不存宿主路径和资料正文。
 * @param tenantId 可信租户
 * @param agentId 归属 Agent
 * @param resourceId 会话或 Run 标识，由 Repository 的方法区分
 * @param selection 用户选择模式及逻辑目录标识
 * @param mappingDigest 服务端目录身份或导入 manifest 身份摘要；未映射时为空
 * @param displayName 选择时的安全展示名称
 * @param directory 已授权的服务器根内相对位置；IMPORTED 必须为空，不保存本机路径
 * @param version 条件更新版本，从 1 开始
 * @param principalId 可信操作主体
 * @param createdAt 创建时间
 * @param updatedAt 最近修改时间
 */
public record WorkspaceBinding(UUID tenantId, UUID agentId, UUID resourceId, WorkspaceSelection selection,
        String mappingDigest, String displayName, String directory, long version, String principalId,
        Instant createdAt, Instant updatedAt) {
    public WorkspaceBinding {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(agentId); Objects.requireNonNull(resourceId);
        Objects.requireNonNull(selection); Objects.requireNonNull(mappingDigest); Objects.requireNonNull(displayName);
        Objects.requireNonNull(directory); Objects.requireNonNull(principalId);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        if (version < 1 || updatedAt.isBefore(createdAt) || principalId.isBlank()
                || selection.mode() == WorkspaceSelection.Mode.SELECTED && mappingDigest.isBlank()
                || selection.mode() == WorkspaceSelection.Mode.IMPORTED && !mappingDigest.matches("[0-9a-f]{64}")
                || selection.mode() == WorkspaceSelection.Mode.IMPORTED && !directory.isEmpty()) {
            throw new IllegalArgumentException("工作区绑定元数据不合法");
        }
    }
}
