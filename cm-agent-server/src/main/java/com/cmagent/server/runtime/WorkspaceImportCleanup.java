package com.cmagent.server.runtime;

import com.cmagent.core.repository.WorkspaceImportRepository;
import com.cmagent.core.runtime.WorkspaceImportStorage;
import com.cmagent.core.domain.WorkspaceImportLease;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;
import java.time.*;
import java.util.UUID;

/**
 * 有界补偿扫描：先持 GC 租约、事务外删除密文、最后确认归还配额。
 * 崩溃发生在任一步都可按元数据重试；任何失败保留容量，不能只改表后遗留公开文件。
 */
@Component
public final class WorkspaceImportCleanup {
    /** 维护访问使用仓储返回的可信归属，不冒充用户或提供 HTTP 跨租户入口。 */
    private final WorkspaceImportRepository repository;
    /** 未配置导入卷时不创建目录，也不能把缺失存储误当删除成功。 */
    private final ObjectProvider<WorkspaceImportStorage> storage;
    /** 仅记录固定原因、随机关联号和可信资源标识。 */
    private static final Logger log = LoggerFactory.getLogger(WorkspaceImportCleanup.class);
    /** @param repository 原子元数据 @param storage 宿主管理的可选密文存储 */
    public WorkspaceImportCleanup(WorkspaceImportRepository repository, ObjectProvider<WorkspaceImportStorage> storage) {
        this.repository = repository; this.storage = storage;
    }
    /** 每批最多100项；多实例竞争由持久租约处理，调度线程不持有 JDBC 长事务。 */
    @Scheduled(fixedDelayString="#{@workspaceImportPolicy.cleanupInterval.toMillis()}")
    public void sweep() {
        var now = Instant.now();
        final java.util.List<com.cmagent.core.domain.WorkspaceImport> candidates;
        try { candidates = repository.cleanupCandidates(now); }
        catch (RuntimeException failure) { diagnostic("SCAN", null, failure); return; }
        for (var batch : candidates) {
            UUID token = null;
            try {
                var objects = storage.getIfAvailable();
                if (objects == null) throw new IllegalStateException("私有卷未配置");
                var lease = repository.acquire(batch.scope(), batch.id(), WorkspaceImportLease.Kind.GC, Duration.ofMinutes(5), Instant.now());
                if (lease.isEmpty()) continue;
                token = lease.get().token(); objects.delete(batch);
                if (!repository.reclaimed(batch.scope(), batch.id(), token, Instant.now())) throw new IllegalStateException("清理租约已失效");
            } catch (Exception failure) { diagnostic("DELETE", batch, failure); }
            finally {
                if (token != null) try { repository.release(batch.scope(), batch.id(), token); }
                catch (RuntimeException failure) { diagnostic("RELEASE", batch, failure); }
            }
        }
    }
    private static void diagnostic(String operation, com.cmagent.core.domain.WorkspaceImport batch, Exception cause) {
        // 文件系统和 JDBC 异常消息可能包含路径/SQL/凭据，只复制栈位置，不附带原始 cause/message。
        var safe = new IllegalStateException("导入清理失败，容量仍保留"); safe.setStackTrace(cause.getStackTrace());
        log.error("errorId={} operation=WORKSPACE_IMPORT_GC_{} errorCode=WORKSPACE_IMPORT_CLEANUP_FAILED tenantId={} resourceType=WORKSPACE_IMPORT resourceId={} exceptionType={}",
                UUID.randomUUID(), operation, batch == null ? "maintenance" : batch.scope().tenantId(), batch == null ? "scan" : batch.id(), cause.getClass().getSimpleName(), safe);
    }
}
