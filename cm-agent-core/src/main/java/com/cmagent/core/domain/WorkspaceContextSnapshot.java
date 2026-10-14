package com.cmagent.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次可信 Run 的不可变只读参考资料，不替代长期会话历史或 Skill 授权。
 * @param tenantId 认证主体所属租户
 * @param agentId 服务端解析的目标 Agent
 * @param runId 原运行标识，审批恢复不能改为新运行
 * @param principalId 原发起主体，不由模型提供
 * @param mappingDigest 管理员映射的摘要；映射变更撤销旧快照，不包含明文路径
 * @param resources 固定资料集合，防御性复制并保持顺序
 * @param sha256 资料集合版本摘要，不随宿主源文件中途改写而变化
 * @param createdAt 首次冻结时间
 */
public record WorkspaceContextSnapshot(UUID tenantId, UUID agentId, UUID runId, String principalId,
        String mappingDigest, List<WorkspaceContextResource> resources, String sha256, Instant createdAt) {
    public WorkspaceContextSnapshot {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(agentId); Objects.requireNonNull(runId); Objects.requireNonNull(createdAt);
        resources = List.copyOf(resources);
        if (principalId == null || principalId.isBlank() || mappingDigest == null || !mappingDigest.matches("[a-f0-9]{64}")
                || resources.isEmpty() || resources.size() > 8 || resources.stream().mapToInt(WorkspaceContextResource::sizeBytes).sum() > 65536
                || resources.stream().map(WorkspaceContextResource::path).distinct().count() != resources.size()
                || !version(resources).equals(sha256)) throw new IllegalArgumentException("工作区快照身份、预算或摘要不合法");
    }

    /** @return 按冻结顺序计算的集合摘要，资料名称和正文变更都会产生新版本 */
    public static String version(List<WorkspaceContextResource> resources) {
        var value = new StringBuilder();
        resources.forEach(resource -> value.append(resource.path()).append('\0').append(resource.sha256()).append('\n'));
        return WorkspaceContextResource.digest(value.toString());
    }
}
