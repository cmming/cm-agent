package com.cmagent.server.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * 指定一次 TEST Run 必须临时注入的技能版本及其映射修订。
 *
 * <p>该对象只能由试运行服务依据可信租户中的技能定义和预检结果构造；运行恢复不接收客户端重传的
 * 选择，而是仅恢复已经持久化的快照，防止候选版本在等待审批期间被替换。</p>
 *
 * @param skillId 临时注入的技能稳定标识
 * @param versionId 临时注入的不可变版本标识
 * @param trialRunId 试运行的稳定 Run 标识
 * @param mappingRevision 开始试运行时锁定的技能依赖映射修订
 */
public record SkillRuntimeSelection(
        UUID skillId,
        UUID versionId,
        UUID trialRunId,
        long mappingRevision
) {
    /** 校验临时选择的稳定身份，避免空标识写入不可恢复快照。 */
    public SkillRuntimeSelection {
        Objects.requireNonNull(skillId, "skillId 不能为空");
        Objects.requireNonNull(versionId, "versionId 不能为空");
        Objects.requireNonNull(trialRunId, "trialRunId 不能为空");
        if (mappingRevision < 0) {
            throw new IllegalArgumentException("mappingRevision 不能为负数");
        }
    }
}
