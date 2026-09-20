package com.cmagent.core.repository;

import com.cmagent.api.ApiPageRequest;
import com.cmagent.api.ApiPageResponse;
import com.cmagent.core.domain.SkillLoadRecord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 技能真实读取尝试及 Run 累计预算的持久化扩展点。 */
public interface SkillLoadRecordRepository {
    /** 插入读取记录；同 Run 的模型调用标识必须唯一。 */
    void insert(SkillLoadRecord record);
    /** 按可信租户、Run 和模型调用标识查询幂等记录。 */
    Optional<SkillLoadRecord> findByCall(UUID tenantId, UUID runId, String modelCallId);
    /** 按尝试序号正序返回用于计算累计次数和字节的全部记录。 */
    List<SkillLoadRecord> listForBudget(UUID tenantId, UUID runId);
    /** 按创建时间和标识倒序分页返回运行读取历史。 */
    ApiPageResponse<SkillLoadRecord> list(UUID tenantId, UUID runId, ApiPageRequest page);
}
