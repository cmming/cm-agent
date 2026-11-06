package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 私有本机根的受保护持久化记录，不作为Web DTO或模型输入。
 * @param id 根登记标识
 * @param tenantId 可信租户
 * @param ownerId 精确原选择主体
 * @param nodeId 唯一本机执行节点
 * @param encryptedDirectory 已由宿主加密器加密的目录，不得存储明文路径
 * @param identity 原目录身份的SHA256
 * @param createdAt 固定登记时间
 */
public record LocalProjectRoot(UUID id, UUID tenantId, String ownerId, String nodeId,
        String encryptedDirectory, String identity, Instant createdAt) {
    public LocalProjectRoot {
        Objects.requireNonNull(id); Objects.requireNonNull(tenantId); Objects.requireNonNull(createdAt);
        LocalProjectCiphertext.require(encryptedDirectory, 262144);
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 200
                || nodeId == null || nodeId.isBlank() || nodeId.length() > 200
                || encryptedDirectory == null || encryptedDirectory.isBlank()
                || identity == null || !identity.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("本地项目根元数据不合法");
    }
    /** 即使调用方误打日志也不输出密文、路径或主体。 */
    @Override public String toString() { return "LocalProjectRoot[受保护的本机根]"; }
}

