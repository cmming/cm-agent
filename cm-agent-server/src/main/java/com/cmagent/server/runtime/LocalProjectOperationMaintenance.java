package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.LocalProjectBinding;
import com.cmagent.core.domain.LocalProjectOperation;
import com.cmagent.core.repository.LocalProjectBindingRepository;
import com.cmagent.core.repository.LocalProjectOperationRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.server.audit.AuditAppender;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 项目操作的元数据查询和 UNKNOWN 人工收口，不执行、撤销或重放本机副作用。
 * 入口必须在外层事务之外调用；内部短事务仅包含条件状态更新与严格审计，拒绝审计在回滚后写入。
 * 不持有文件/进程/正文解密能力，七天擦除后的墓碑仍可由原主体核对终结。
 */
public final class LocalProjectOperationMaintenance {
    /** 持久意图和版本竞争，不使用请求布尔值充当 HIGH 批准。 */
    private final LocalProjectOperationRepository operations;
    /** 长期固定来源，不能由当前目录选择代替。 */
    private final LocalProjectBindingRepository bindings;
    /** 原运行主体及不可降级的来源头。 */
    private final RunRepository runs;
    /** 权限、项目元数据和同号安全诊断；不调用其活根 scope。 */
    private final LocalProjectAccessService access;
    /** 纯元数据来源校验，支持归档历史且不打开根目录。 */
    private final LocalProjectResourceAccess resources;
    /** 和状态 CAS 共用短事务的严格审计。 */
    private final AuditAppender audit;
    /** 必须绑定与意图仓储及审计相同的数据源。 */
    private final TransactionTemplate transaction;
    /** 状态推进使用可信宿主时钟，不接受客户端时间。 */
    private final Clock clock;

    /**
     * @param operations 持久操作仓储
     * @param bindings 长期来源仓储
     * @param runs 原运行仓储
     * @param access 权限与安全诊断边界
     * @param resources 不触碰文件系统的项目来源核验
     * @param audit 严格审计入口，须参与同一事务
     * @param transaction 与仓储和审计共用数据源的短事务
     * @param clock 可信时间来源
     */
    public LocalProjectOperationMaintenance(LocalProjectOperationRepository operations, LocalProjectBindingRepository bindings,
            RunRepository runs, LocalProjectAccessService access, LocalProjectResourceAccess resources, AuditAppender audit,
            TransactionTemplate transaction, Clock clock) {
        this.operations = Objects.requireNonNull(operations); this.bindings = Objects.requireNonNull(bindings);
        this.runs = Objects.requireNonNull(runs); this.access = Objects.requireNonNull(access);
        this.resources = Objects.requireNonNull(resources); this.audit = Objects.requireNonNull(audit);
        this.transaction = Objects.requireNonNull(transaction); this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 只返回原 owner 当前可读的操作元数据，不解密或返回过期正文。
     * @param principal 认证主体
     * @param projectId 当前项目
     * @param runId 项目固定运行
     * @param limit 单页上限，限定 1 到 100
     * @return 按创建时间和标识倒序排列的安全视图
     */
    public List<View> listRun(PrincipalRef principal, UUID projectId, UUID runId, int limit) {
        requireNoOuterTransaction();
        try {
            if (limit < 1 || limit > 100) throw denied("PROJECT_OPERATION_LIMIT_INVALID", 400, "操作列表上限必须在1到100之间");
            var source = source(principal, projectId, runId, "project:read");
            return operations.listRun(principal.tenantId(), principal.principalId(), projectId, runId, limit).stream()
                    .map(value -> {
                        requireOwned(principal, projectId, runId, value);
                        requireOrigin(source, value);
                        return view(value);
                    }).toList();
        } catch (Rejected failure) { throw access.reject(principal, runId, failure.code, failure.status, failure.getMessage()); }
        catch (LocalProjectAccessException failure) { throw failure; }
        catch (RuntimeException failure) { throw access.infrastructure(principal, runId, "PROJECT_OPERATION_LIST", failure); }
    }

    /**
     * 原主体人工核对后终结 UNKNOWN 原意图；确认只接受可能已有副作用，不批准任何后续命令或变更。
     * 请求不得包在外层事务中；同一版本最多成功一次，审计失败和持久化失败均回滚并安全诊断。
     * @param principal 认证原主体
     * @param projectId 固定项目
     * @param runId 固定运行
     * @param operationId 原操作
     * @param expectedVersion 页面读取的精确版本，不自动刷新后重试
     * @param acknowledgeExternalEffects 明确表示已人工核对并接受可能已有副作用，不能充当 HIGH 批准
     * @return CANCELLED 的安全元数据视图；不表示已执行成功或已撤销外部副作用
     */
    public View resolveUnknown(PrincipalRef principal, UUID projectId, UUID runId, UUID operationId,
            long expectedVersion, boolean acknowledgeExternalEffects) {
        requireNoOuterTransaction();
        try {
            var source = source(principal, projectId, runId, "project:manage");
            // UNKNOWN 收口会解除新操作阻断，必须由原执行节点负责；此核验只读节点元数据，不打开活根。
            access.requireExecutionNode(principal, projectId);
            access.authorize(principal, "agent:run", runId);
            var current = owned(principal, projectId, runId, operationId);
            requireOrigin(source, current);
            access.authorize(principal, current.kind() == LocalProjectOperation.Kind.COMMAND ? "project:execute" : "project:write", projectId);
            if (!acknowledgeExternalEffects) throw denied("PROJECT_OPERATION_ACKNOWLEDGEMENT_REQUIRED", 400, "请先人工核对并明确接受可能已经发生的外部副作用");
            requireUnknown(current, expectedVersion);
            return Objects.requireNonNull(transaction.execute(status -> {
                // 事务内只抛出不带审计副作用的受控拒绝；外层回滚完成后再由 access 记录稳定错误。
                var locked = owned(principal, projectId, runId, operationId);
                requireOrigin(source, locked);
                requireUnknown(locked, expectedVersion);
                var cancelled = operations.advance(principal.tenantId(), principal.principalId(), operationId, expectedVersion,
                        LocalProjectOperation.State.CANCELLED, clock.instant())
                        .orElseThrow(() -> denied("PROJECT_OPERATION_CONFLICT", 409, "操作状态或版本已变化，请刷新后核对原操作"));
                audit.appendControlled(principal.tenantId(), principal.principalId(), "LOCAL_PROJECT_OPERATION_RESOLVE_UNKNOWN",
                        "PROJECT_OPERATION", operationId.toString(), "CANCELLED", "runId=" + runId + " projectId=" + projectId
                        + " digest=" + locked.digest() + " previousState=UNKNOWN previousVersion=" + expectedVersion
                        + " resolution=MANUALLY_ACKNOWLEDGED_POSSIBLE_EXTERNAL_EFFECTS noReplay=true");
                return view(cancelled);
            }));
        } catch (Rejected failure) { throw access.reject(principal, operationId, failure.code, failure.status, failure.getMessage()); }
        catch (LocalProjectAccessException failure) { throw failure; }
        catch (RuntimeException failure) { throw access.infrastructure(principal, operationId, "PROJECT_OPERATION_RESOLVE_UNKNOWN", failure); }
    }

    private LocalProjectBinding source(PrincipalRef principal, UUID projectId, UUID runId, String permission) {
        access.project(principal, projectId, permission);
        var binding = bindings.run(principal.tenantId(), principal.principalId(), runId)
                .orElseThrow(() -> denied("PROJECT_OPERATION_NOT_FOUND", 404, "项目操作不存在或不可访问"));
        if (!binding.tenantId().equals(principal.tenantId()) || !binding.ownerId().equals(principal.principalId())
                || !binding.projectId().equals(projectId) || !Objects.equals(binding.runId(), runId))
            throw denied("PROJECT_OPERATION_NOT_FOUND", 404, "项目操作不存在或不可访问");
        var run = runs.findByTenantAndAgentAndId(principal.tenantId(), binding.agentId(), runId)
                .orElseThrow(() -> denied("PROJECT_OPERATION_NOT_FOUND", 404, "项目操作不存在或不可访问"));
        if (!resources.run(principal, run).equals(binding))
            throw denied("PROJECT_OPERATION_NOT_FOUND", 404, "项目操作不存在或不可访问");
        return binding;
    }

    private LocalProjectOperation owned(PrincipalRef principal, UUID projectId, UUID runId, UUID operationId) {
        var value = operations.operation(principal.tenantId(), principal.principalId(), operationId)
                .orElseThrow(() -> denied("PROJECT_OPERATION_NOT_FOUND", 404, "项目操作不存在或不可访问"));
        requireOwned(principal, projectId, runId, value);
        return value;
    }

    private static void requireOwned(PrincipalRef principal, UUID projectId, UUID runId, LocalProjectOperation value) {
        if (!value.tenantId().equals(principal.tenantId()) || !value.ownerId().equals(principal.principalId())
                || !value.projectId().equals(projectId) || !value.runId().equals(runId))
            throw denied("PROJECT_OPERATION_NOT_FOUND", 404, "项目操作不存在或不可访问");
    }

    private static void requireUnknown(LocalProjectOperation value, long expectedVersion) {
        if (expectedVersion < 1 || value.version() != expectedVersion || value.state() != LocalProjectOperation.State.UNKNOWN)
            throw denied("PROJECT_OPERATION_CONFLICT", 409, "操作不是待核对的未知状态或版本已变化，请刷新后核对原操作");
    }

    private static void requireOrigin(LocalProjectBinding source, LocalProjectOperation value) {
        if (!source.agentId().equals(value.agentId()) || !source.conversationId().equals(value.conversationId()))
            throw denied("PROJECT_OPERATION_NOT_FOUND", 404, "项目操作不存在或不可访问");
    }

    private static void requireNoOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("项目操作元数据入口不能在外层事务内调用");
    }

    private static Rejected denied(String code, int status, String reason) { return new Rejected(code, status, reason); }

    private static View view(LocalProjectOperation value) {
        String notice = switch (value.state()) {
            case UNKNOWN -> "执行结果未知，请先人工核对可能已经发生的副作用";
            case CANCELLED -> "原意图已终结；不表示已撤销可能发生的副作用";
            default -> "仅展示操作元数据，执行详情以受控操作接口为准";
        };
        return new View(value.id(), value.projectId(), value.runId(), value.kind(), value.state(), value.version(), value.digest(),
                value.createdAt(), value.updatedAt(), notice);
    }

    /** 只在本类事务回滚后映射为已审计拒绝，不携带数据库、路径或正文异常。 */
    private static final class Rejected extends RuntimeException {
        /** 固定错误码，禁止使用请求提供的值。 */ private final String code;
        /** 与错误码一致的 HTTP 状态。 */ private final int status;
        private Rejected(String code, int status, String reason) { super(reason); this.code = code; this.status = status; }
    }

    /**
     * 操作安全元数据，不包含 encryptedPayload、文件正文、参数、环境或宿主路径。
     * @param operationId 原操作标识
     * @param projectId 固定项目
     * @param runId 固定运行
     * @param kind 文件变更或命令类型
     * @param state 当前持久状态
     * @param version 当前精确版本
     * @param digest 原清单摘要，人工收口不改变它
     * @param createdAt 原创建时间
     * @param updatedAt 最近条件状态更新时间
     * @param notice 安全中文状态说明，不据此承诺外部结果
     */
    public record View(UUID operationId, UUID projectId, UUID runId, LocalProjectOperation.Kind kind, LocalProjectOperation.State state,
            long version, String digest, Instant createdAt, Instant updatedAt, String notice) {}
}
