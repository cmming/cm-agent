package com.cmagent.core.repository;

import com.cmagent.core.domain.*;
import java.time.*;
import java.util.*;

/**
 * 导入元数据原子合同；所有普通方法都要求可信 scope，越界与不存在返回同样的空结果。
 * 实现须串行化同租户配额与租约变更，文件/模型 I/O 不得进入这些短事务。
 */
public interface WorkspaceImportRepository {
    /** @return 原子预留是否成功；重复标识/配额不足不覆盖既有 manifest */
    boolean reserve(WorkspaceImport draft, WorkspaceImportLimits limits, Instant now);
    /** @return 仅原 tenant/owner/Agent 可见的不可变批次，包含已撤销/过期状态供核对 */
    Optional<WorkspaceImport> find(WorkspaceImportScope scope, UUID id);
    /** @return 仅按原tenant/owner/Agent查找同一次创建结果；未找到与不可见统一为空，不重试创建 */
    Optional<WorkspaceImport> findByRequest(WorkspaceImportScope scope, UUID requestId);
    /** @return 持有效独占上传租约及匹配版本时确认已持久化文件；重复确认拒绝而非覆盖 */
    Optional<WorkspaceImport> stored(WorkspaceImportScope scope, UUID id, UUID file, long version, UUID token, Instant now);
    /** @return 全文件已确认且持有效上传租约时发布 READY；调用方必须先在事务外复核密文 */
    Optional<WorkspaceImport> complete(WorkspaceImportScope scope, UUID id, long version, UUID token, WorkspaceImportLimits limits, Instant now);
    /** @return 原主体撤销后的状态；不受上传开关影响，逻辑撤销不等待在途读取结束 */
    Optional<WorkspaceImport> revoke(WorkspaceImportScope scope, UUID id, long version, Instant now);
    /** @return 获取短租约；新 READ 仅 READY/期限内允许，过期/撤销仍保留原读取租约阻止 GC */
    Optional<WorkspaceImportLease> acquire(WorkspaceImportScope scope, UUID id, WorkspaceImportLease.Kind kind, Duration duration, Instant now);
    /** @return 仅有效令牌可续租，撤销/过期后 READ 不续租，调用方应结束运行或读取 */
    boolean renew(WorkspaceImportScope scope, UUID id, UUID token, Duration duration, Instant now);
    /** 仅释放本令牌，不删除其他运行持有的引用。 */
    void release(WorkspaceImportScope scope, UUID id, UUID token);
    /** @return GC 完成物理删除后才可确认并归还容量；无有效 GC 令牌时不生效 */
    boolean reclaimed(WorkspaceImportScope scope, UUID id, UUID token, Instant now);
    /**
     * 仅服务端维护入口允许跨租户扫描；不能由 Controller 暴露，不包含正文。
     * @return 最多100条到期/撤销且未回收候选；实际 GC 仍须 acquire 原子复核租约
     */
    List<WorkspaceImport> cleanupCandidates(Instant now);
}
