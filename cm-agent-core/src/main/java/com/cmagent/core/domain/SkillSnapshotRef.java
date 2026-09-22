package com.cmagent.core.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Run 创建时固定的技能版本、授权来源、访问纪元和依赖映射。
 *
 * @param skillId 技能稳定标识
 * @param versionId 固定版本标识
 * @param origin 版本来自正式绑定还是试运行临时注入
 * @param authorizationId 正式绑定标识或试运行 Run 标识
 * @param accessEpoch 创建快照时的技能访问纪元
 * @param dependencies 创建 Run 时固定的逻辑依赖解析
 */
public record SkillSnapshotRef(
        UUID skillId,
        UUID versionId,
        SkillSnapshotOrigin origin,
        UUID authorizationId,
        long accessEpoch,
        List<SkillDependencyResolution> dependencies
) {
    /** 冻结依赖映射并拒绝重复逻辑键，恢复时不得重新读取可变环境映射。 */
    public SkillSnapshotRef {
        Objects.requireNonNull(skillId, "skillId 不能为空");
        Objects.requireNonNull(versionId, "versionId 不能为空");
        Objects.requireNonNull(origin, "origin 不能为空");
        Objects.requireNonNull(authorizationId, "authorizationId 不能为空");
        if (accessEpoch < 0) {
            throw new IllegalArgumentException("技能访问纪元不能为负数");
        }
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies 不能为空"));
        Set<String> keys = new HashSet<>();
        if (dependencies.stream().anyMatch(dependency -> !keys.add(dependency.logicalKey()))) {
            throw new IllegalArgumentException("技能依赖解析键不能重复");
        }
    }

    /** 兼容 format 1 快照构造，旧 bindingId 转为正式绑定来源且依赖为空。 */
    public SkillSnapshotRef(UUID skillId, UUID versionId, UUID bindingId, long accessEpoch) {
        this(skillId, versionId, SkillSnapshotOrigin.BINDING, bindingId, accessEpoch, List.of());
    }

    /** @return 兼容 format 1 调用方使用的正式绑定标识 */
    public UUID bindingId() {
        return authorizationId;
    }
}
