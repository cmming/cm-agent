package com.cmagent.core.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Run 创建时固定的技能版本、绑定身份和访问纪元。
 *
 * @param skillId 技能稳定标识
 * @param versionId 固定版本标识
 * @param bindingId 创建快照时的绑定标识
 * @param accessEpoch 创建快照时的技能访问纪元
 */
public record SkillSnapshotRef(
        UUID skillId,
        UUID versionId,
        UUID bindingId,
        long accessEpoch
) {

    /** 校验快照引用完整且纪元非负。 */
    public SkillSnapshotRef {
        Objects.requireNonNull(skillId, "skillId 不能为空");
        Objects.requireNonNull(versionId, "versionId 不能为空");
        Objects.requireNonNull(bindingId, "bindingId 不能为空");
        if (accessEpoch < 0) {
            throw new IllegalArgumentException("技能访问纪元不能为负数");
        }
    }
}
