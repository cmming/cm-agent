package com.cmagent.server.service;

import com.cmagent.api.ApiPageRequest;
import com.cmagent.api.ApiPageResponse;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillLoadRecord;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillLoadRecordRepository;
import com.cmagent.core.repository.SkillVersionRepository;
import com.cmagent.server.web.SkillResponses;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 在 Run 已完成归属校验后，分页组装技能读取记录的历史视图。 */
@Service
public class SkillLoadQueryService {
    private final SkillLoadRecordRepository records;
    private final SkillDefinitionRepository definitions;
    private final SkillVersionRepository versions;

    /**
     * @param records 技能读取记录仓储
     * @param definitions 技能稳定身份仓储
     * @param versions 不可变历史版本仓储
     */
    public SkillLoadQueryService(SkillLoadRecordRepository records, SkillDefinitionRepository definitions,
                                 SkillVersionRepository versions) {
        this.records = records;
        this.definitions = definitions;
        this.versions = versions;
    }

    /**
     * 只按记录中固定的版本标识解析名称和版本号，绝不回退到技能当前版本。
     *
     * @param tenantId 已通过认证得到的租户标识
     * @param runId 已验证归属的运行标识
     * @param page 页码与页容量
     * @return 不暴露正文的历史读取记录
     */
    public ApiPageResponse<SkillResponses.Load> list(UUID tenantId, UUID runId, ApiPageRequest page) {
        ApiPageResponse<SkillLoadRecord> result = records.list(tenantId, runId, page);
        return new ApiPageResponse<>(result.items().stream().map(record -> load(tenantId, record)).toList(),
                result.total(), result.page(), result.size());
    }

    private SkillResponses.Load load(UUID tenantId, SkillLoadRecord record) {
        SkillDefinition definition = record.skillId() == null
                ? null : definitions.find(tenantId, record.skillId()).orElse(null);
        Integer versionNo = record.skillId() == null || record.versionId() == null
                ? null : versions.find(tenantId, record.skillId(), record.versionId())
                .map(version -> version.versionNo()).orElse(null);
        return new SkillResponses.Load(record.id(), record.skillId(),
                definition == null ? null : definition.name(), record.versionId(), versionNo,
                record.path(), record.status(), record.deliveredBytes(), record.durationMillis(),
                record.errorCode(), record.errorId(), record.createdAt());
    }
}
