package com.cmagent.core.repository;

import com.cmagent.core.domain.RunSkillSnapshot;

import java.util.Optional;
import java.util.UUID;

/** Run 固定技能授权快照的持久化扩展点。 */
public interface RunSkillSnapshotRepository {
    /** 插入一次 Run 的快照，空技能集合也必须保存。 */
    void insert(RunSkillSnapshot snapshot);
    /** 按可信租户和 Run 标识查询。 */
    Optional<RunSkillSnapshot> find(UUID tenantId, UUID runId);
    /** 在当前工作单元中锁定快照；不存在时抛出受控异常。 */
    RunSkillSnapshot lock(UUID tenantId, UUID runId);
}
