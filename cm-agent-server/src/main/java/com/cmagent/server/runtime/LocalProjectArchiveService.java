package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.LocalProject;
import com.cmagent.core.repository.*;
import com.cmagent.server.audit.AuditAppender;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 项目纯元数据归档；没有文件、进程或正文解密依赖，不删除本地目录与历史。 */
public final class LocalProjectArchiveService {
    /** 原owner及project:manage校验、最终安全拒绝/诊断。 */ private final LocalProjectAccessService access;
    /** 与新Run登记/派发相同的项目行锁和归档CAS。 */ private final LocalProjectRepository projects;
    /** 已知固定Run的所有活动态阻断。 */ private final LocalProjectBindingRepository bindings;
    /** RUNNING/UNKNOWN副作用不能被归档隐藏。 */ private final LocalProjectOperationRepository operations;
    /** 归档CAS与成功审计同事务，拒绝审计在事务外写入。 */ private final AuditAppender audit;
    /** 同数据源短事务，不可包含原生副作用。 */ private final TransactionTemplate transaction;
    /** 固定归档时间来自宿主。 */ private final Clock clock;

    /**
     * @param access 原主体授权及安全诊断
     * @param projects 项目行锁和条件更新
     * @param bindings 活动Run见证
     * @param operations 未核对副作用见证
     * @param audit 参与同一事务的严格审计
     * @param transaction 与上述仓储及审计共用数据源，入口不得已有事务
     * @param clock 服务端时钟
     */
    public LocalProjectArchiveService(LocalProjectAccessService access, LocalProjectRepository projects, LocalProjectBindingRepository bindings,
            LocalProjectOperationRepository operations, AuditAppender audit, TransactionTemplate transaction, Clock clock) {
        this.access = Objects.requireNonNull(access); this.projects = Objects.requireNonNull(projects); this.bindings = Objects.requireNonNull(bindings);
        this.operations = Objects.requireNonNull(operations); this.audit = Objects.requireNonNull(audit);
        this.transaction = Objects.requireNonNull(transaction); this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 在项目锁下核对活动及未知意图后归档；已归档返回原元数据，不重做审计或改写版本。
     * @param principal 认证原主体，不能用请求owner覆盖
     * @param projectId 当前私有项目
     * @param expectedVersion 页面读取版本，不自动刷新后重试
     * @return 不含路径的项目元数据；目录与历史原样保留
     */
    public LocalProjectService.View archive(PrincipalRef principal, UUID projectId, long expectedVersion) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("项目归档入口不得处于外层事务中");
        try {
            access.project(principal, projectId, "project:manage");
            if (expectedVersion < 1) throw new Rejected("PROJECT_VERSION_INVALID", 400, "项目版本无效，请刷新后重试");
            return Objects.requireNonNull(transaction.execute(status -> {
                var project = projects.lock(principal.tenantId(), principal.principalId(), projectId)
                        .orElseThrow(() -> new Rejected("PROJECT_NOT_FOUND", 404, "项目不存在或不可访问"));
                if (!project.tenantId().equals(principal.tenantId()) || !project.ownerId().equals(principal.principalId()) || !project.id().equals(projectId))
                    throw new Rejected("PROJECT_NOT_FOUND", 404, "项目不存在或不可访问");
                if (project.state() == LocalProject.State.ARCHIVED) return view(project);
                if (project.version() != expectedVersion) throw new Rejected("PROJECT_VERSION_CONFLICT", 409, "项目版本已变化，请刷新后核对再归档");
                if (bindings.hasActiveRuns(principal.tenantId(), principal.principalId(), projectId))
                    throw new Rejected("PROJECT_ACTIVE_RUNS", 409, "项目仍有运行中、待审批或停止中的任务，请先停止并核对运行状态");
                if (operations.hasUncertainOperation(principal.tenantId(), principal.principalId(), projectId, null))
                    throw new Rejected("PROJECT_UNCERTAIN_OPERATIONS", 409, "项目仍有执行中或结果未知的本机操作，请先核对并收口原操作");
                var now = clock.instant();
                if (!projects.archive(principal.tenantId(), principal.principalId(), projectId, expectedVersion, now))
                    throw new Rejected("PROJECT_VERSION_CONFLICT", 409, "项目状态或版本已变化，请刷新后核对再归档");
                var archived = project.archive(now);
                audit.appendControlled(principal.tenantId(), principal.principalId(), "PROJECT_ARCHIVE", "LOCAL_PROJECT", projectId.toString(),
                        "ARCHIVED", "仅归档项目登记，本地目录及历史保留 version=" + archived.version());
                return view(archived);
            }));
        } catch (Rejected failure) { throw access.reject(principal, projectId, failure.code, failure.status, failure.getMessage()); }
        catch (LocalProjectAccessException failure) { throw failure; }
        catch (RuntimeException failure) { throw access.infrastructure(principal, projectId, "PROJECT_ARCHIVE", failure); }
    }

    private static LocalProjectService.View view(LocalProject value) {
        return new LocalProjectService.View(value.id(), value.name(), value.state().name(), value.version(), value.createdAt(), value.updatedAt());
    }
    /** 事务内只传递受控原因，回滚完成后才写拒绝审计，防止拒绝证据随事务消失。 */
    private static final class Rejected extends RuntimeException {
        /** 固定错误分类。 */ private final String code;
        /** 固定HTTP语义。 */ private final int status;
        private Rejected(String code, int status, String message) { super(message); this.code = code; this.status = status; }
    }
}
