package com.cmagent.core.repository;
import com.cmagent.core.domain.SkillArtifact;
import java.time.*;
import java.util.*;
/** 持久化产物元数据与跨实例配额/租约，不持有文件字节。 */
public interface SkillArtifactRepository {
    /** 原子预留配额并写入 STAGING；幂等键重复或配额不足返回 false。 */
    boolean reserve(SkillArtifact artifact,long runLimit,long tenantLimit,int runFiles,int tenantFiles);
    /** 同租户查询，跨租户返回空。 */
    Optional<SkillArtifact> find(UUID tenantId,UUID id);
    /** 返回单 Run 的有界元数据，包含状态但不包含字节内容。 */
    List<SkillArtifact> list(UUID tenantId,UUID runId);
    /** 文件已完成持久化才允许由 STAGING 转为 PENDING。 */
    boolean stored(UUID tenantId,UUID id,String sha256);
    /** 与成功 Run 和严格审计共享工作单元，将 PENDING 转为 READY。 */
    void publish(UUID tenantId,UUID runId,Instant expiresAt);
    /** 撤销未交付或失败运行的所有文件；实际删除仍通过资源租约。 */
    void discard(UUID tenantId,UUID runId);
    /** 单次脚本失败只撤销同一调用的暂存文件，不误删其他成功调用的文件。 */
    void discardCall(UUID tenantId,UUID runId,String callId);
    /** 原子获取读取或删除租约；只能替换已过期租约。 */
    boolean lease(UUID tenantId,UUID id,UUID token,Instant now,Instant until,boolean deleting);
    /** 仅当前租约令牌可以确认删除或释放读取租约，防止旧实例误清理。 */
    void release(UUID tenantId,UUID id,UUID token,boolean deleted);
    /**
     * 仅供服务端清理器扫描，跨租户结果不可用于 Web 列表。
     * @param now 当前时间
     * @param abandoned 暂存遗留截止时间
     * @return 至多 100 条待清理候选，调用方仍需复核运行和租约
     */
    List<SkillArtifact> cleanupCandidates(Instant now,Instant abandoned);
}
