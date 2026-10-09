package com.cmagent.core.domain;
import java.time.Instant;
import java.util.UUID;
import java.util.Objects;
/**
 * 文件元数据，不包含内容、密钥、磁盘路径或下载令牌。
 * @param id 产物唯一标识
 * @param tenantId 可信租户标识
 * @param principalId 运行创建者标识
 * @param agentId 所属 Agent 标识
 * @param runId 所属运行标识
 * @param runKind 正式或 TEST 运行
 * @param skillId 固定技能标识
 * @param versionId 固定技能版本标识
 * @param callId 原模型调用标识
 * @param filename 经过校验的展示文件名
 * @param mediaType 受控附件媒体类型
 * @param sizeBytes 明文字节数
 * @param sha256 服务端计算的摘要，暂存时为空
 * @param status 产物生命周期状态
 * @param createdAt 创建时间
 * @param expiresAt 有效期截止时间
 * @param leaseToken 当前资源租约令牌，可为空
 * @param leaseUntil 资源租约期限，可为空
 */
public record SkillArtifact(
        UUID id,
        UUID tenantId,
        String principalId,
        UUID agentId,
        UUID runId,
        RunKind runKind,
        UUID skillId,
        UUID versionId,
        String callId,
        String filename,
        String mediaType,
        long sizeBytes,
        String sha256,
        SkillArtifactStatus status,
        Instant createdAt,
        Instant expiresAt,
        UUID leaseToken,
        Instant leaseUntil) {
    /** 所有归属必须在服务端从可信 Run 和技能快照构造。 */
    public SkillArtifact {
        Objects.requireNonNull(id); Objects.requireNonNull(tenantId); Objects.requireNonNull(agentId);
        Objects.requireNonNull(runId); Objects.requireNonNull(runKind); Objects.requireNonNull(skillId);
        Objects.requireNonNull(versionId); Objects.requireNonNull(status); Objects.requireNonNull(createdAt);
        Objects.requireNonNull(expiresAt);
        if(principalId==null || principalId.isBlank() || callId==null || callId.isBlank()
                || filename==null || filename.isBlank() || mediaType==null || sizeBytes<1)
            throw new IllegalArgumentException("产物元数据不合法");
        if((leaseToken==null)!=(leaseUntil==null)) throw new IllegalArgumentException("产物租约不完整");
    }
    /** 返回新状态副本；租约只由 Repository 的条件更新控制。 */
    public SkillArtifact change(SkillArtifactStatus next,String digest,Instant expiry,UUID token,Instant until) {
        return new SkillArtifact(id,tenantId,principalId,agentId,runId,runKind,skillId,versionId,callId,
            filename,mediaType,sizeBytes,digest,next,createdAt,expiry,token,until);
    }
}
