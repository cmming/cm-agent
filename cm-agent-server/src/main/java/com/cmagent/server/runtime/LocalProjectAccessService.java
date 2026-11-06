package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import java.util.Objects;
import java.util.UUID;

/** 项目/会话/Run统一私有边界；同租户管理员也不能隐式读取他人目录。 */
public final class LocalProjectAccessService {
    /** 拒绝日志仅包含可信编号/身份，不复制路径、展示名或正文。 */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalProjectAccessService.class);
    /** 长期元数据只按当前tenant及精确owner查询。 */
    private final LocalProjectRepository projects;
    /** 根密文不是API对象。 */
    private final LocalProjectRootRepository roots;
    /** 不可变来源，不能从当前Agent工作区猜测。 */
    private final LocalProjectBindingRepository bindings;
    /** 可信RBAC判定。 */
    private final PermissionEvaluator permissions;
    /** 拒绝和成功操作都不能绕过严格审计。 */
    private final AuditAppender audit;
    /** 最终错误边界，编号同时返回页面。 */
    private final ErrorDiagnosticLogger diagnostics;
    /** 根上下文认证与当前固定节点。 */
    private final LocalProjectRootCipher cipher;
    /** 活根/路径策略复核，不提供目录明文给外层。 */
    private final LocalProjectFiles files;
    /** 来自部署而非请求的节点身份。 */
    private final String nodeId;

    LocalProjectAccessService(LocalProjectRepository projects, LocalProjectRootRepository roots,
            LocalProjectBindingRepository bindings, PermissionEvaluator permissions, AuditAppender audit,
            ErrorDiagnosticLogger diagnostics, LocalProjectRootCipher cipher, LocalProjectFiles files, String nodeId) {
        this.projects = projects; this.roots = roots; this.bindings = bindings; this.permissions = permissions;
        this.audit = audit; this.diagnostics = diagnostics; this.cipher = cipher; this.files = files; this.nodeId = nodeId;
    }

    /** @param principal 认证主体 @param projectId 项目标识 @param permission 固定权限码 @return 当前主体可见项目；无权限或不存在均安全拒绝 */
    public LocalProject project(PrincipalRef principal, UUID projectId, String permission) {
        authorize(principal, permission, projectId);
        try {
            return projects.find(principal.tenantId(), principal.principalId(), projectId)
                    .orElseThrow(() -> reject(principal, projectId, "PROJECT_NOT_FOUND", 404, "项目不存在或不可访问"));
        } catch (LocalProjectAccessException failure) { throw failure; }
        catch (RuntimeException failure) { throw infrastructure(principal, projectId, "PROJECT_LOOKUP", failure); }
    }

    /**
     * 人工终结未知意图只需元数据，但仍须当前原执行节点，不能在另一节点解除副作用阻断。
     * @param principal 认证主体 @param projectId 当前项目 @return 当前节点上原owner可管理的项目，不打开本机根
     */
    public LocalProject requireExecutionNode(PrincipalRef principal,UUID projectId) {
        var project=project(principal,projectId,"project:manage");
        if(!nodeId.equals(project.nodeId()))throw reject(principal,projectId,"PROJECT_NODE_UNAVAILABLE",409,"请在项目原执行节点核对并终结未知意图");
        return project;
    }

    /** 当前Project的受信根只在runtime内部可见，每次访问都重核根身份及私有目录排除。 */
    Scope scope(PrincipalRef principal, UUID projectId, String permission) {
        LocalProject project = project(principal, projectId, permission);
        if (project.state() != LocalProject.State.ACTIVE)
            throw reject(principal, projectId, "PROJECT_ARCHIVED", 409, "项目已归档，请使用活动项目");
        if (!nodeId.equals(project.nodeId()))
            throw reject(principal, projectId, "PROJECT_NODE_UNAVAILABLE", 409, "项目所属执行节点不可用");
        try {
            var record = roots.root(principal.tenantId(), principal.principalId(), project.rootBindingId()).orElseThrow();
            if (!record.identity().equals(project.rootIdentity())) throw new IllegalStateException();
            var root = cipher.open(principal, record);
            files.list(root, "", null, 1);
            return new Scope(principal, project, root, null);
        } catch (LocalProjectFileException failure) { throw fileFailure(principal, projectId, failure); }
        catch (Exception failure) { throw infrastructure(principal, projectId, "PROJECT_ROOT_OPEN", failure); }
    }

    /** 运行快照必须精确匹配原项目、Agent、会话、根与节点；重命名不把旧Run切换到新根。 */
    Scope run(PrincipalRef principal, UUID runId, String permission) {
        try {
            LocalProjectBinding binding = bindings.run(principal.tenantId(), principal.principalId(), runId)
                    .orElseThrow(() -> reject(principal, runId, "PROJECT_NOT_FOUND", 404, "项目运行不存在或不可访问"));
            Scope scope = scope(principal, binding.projectId(), permission);
            if (!Objects.equals(binding.runId(), runId) || !binding.nodeId().equals(scope.project().nodeId())
                    || !binding.rootBindingId().equals(scope.project().rootBindingId())
                    || !binding.rootIdentity().equals(scope.project().rootIdentity()))
                throw reject(principal, runId, "PROJECT_BINDING_CONFLICT", 409, "项目运行来源已失效，请新建会话");
            return new Scope(principal, scope.project(), scope.root(), binding);
        } catch (LocalProjectAccessException failure) { throw failure; }
        catch (RuntimeException failure) { throw infrastructure(principal, runId, "PROJECT_RUN_LOOKUP", failure); }
    }

    /** 入口调用方只能传代码中的固定权限，权限集合来自认证主体而非请求体。 */
    public void authorize(PrincipalRef principal, String permission, UUID resourceId) {
        if (!permissions.check(principal, permission).allowed())
            throw reject(principal, resourceId, "PROJECT_FORBIDDEN", 403, "当前账号缺少本地项目操作权限");
    }

    LocalProjectAccessException fileFailure(PrincipalRef principal, UUID id, LocalProjectFileException failure) {
        int status = switch (failure.reason()) {
            case INVALID_PATH -> 400;
            case NOT_FOUND -> 404;
            case ACCESS_DENIED, EXCLUDED, UNSAFE_FILE -> 403;
            case ROOT_CHANGED, FILE_CHANGED, FILE_BUSY -> 409;
            case UNAVAILABLE -> 503;
            case TIMEOUT -> 504;
            default -> 422;
        };
        return reject(principal, id, "PROJECT_FILE_" + failure.reason().name(), status, failure.getMessage());
    }

    /** 预期拒绝进入安全审计，不以ERROR堆栈淹没权限事件；审计失败仍作为基础设施失败收口。 */
    public LocalProjectAccessException reject(PrincipalRef principal, UUID id, String code, int status, String reason) {
        String errorId = UUID.randomUUID().toString();
        try {
            audit.appendControlled(principal.tenantId(), principal.principalId(), "LOCAL_PROJECT_ACCESS", "LOCAL_PROJECT",
                    id == null ? "-" : id.toString(), "DENIED", "errorCode=" + code + " errorId=" + errorId);
        } catch (RuntimeException failure) { return infrastructure(principal, id, "PROJECT_AUDIT", failure); }
        log.warn("errorId={} operation=LOCAL_PROJECT_ACCESS errorCode={} tenantId={} principalId={} resourceType=LOCAL_PROJECT resourceId={}",
                errorId, code, principal.tenantId(), principal.principalId(), id == null ? "-" : id);
        return new LocalProjectAccessException(code, errorId, status, reason);
    }

    /** 在进入文件/原生副作用前记录STARTED，实际成功后另记SUCCEEDED；审计故障阻止交付或继续执行。 */
    void record(PrincipalRef principal, UUID id, String action, String status) {
        try {
            audit.appendControlled(principal.tenantId(), principal.principalId(), action, "LOCAL_PROJECT",
                    id == null ? "-" : id.toString(), status, "本机项目显式操作");
        } catch (RuntimeException failure) { throw infrastructure(principal, id, "PROJECT_AUDIT", failure); }
    }

    /** 宿主异常可能含绝对路径/SQL，只保留堆栈位置；不把底层消息或cause送入日志/响应。 */
    LocalProjectAccessException infrastructure(PrincipalRef principal, UUID id, String action, Throwable failure) {
        String errorId = UUID.randomUUID().toString();
        var safe = new IllegalStateException("本地项目基础设施调用失败");
        safe.setStackTrace(failure.getStackTrace());
        diagnostics.error(new ErrorDiagnosticLogger.DiagnosticContext(errorId, action, "PROJECT_UNAVAILABLE",
                principal.tenantId().toString(), principal.principalId(), null, null, null, null, "LOCAL_PROJECT"),
                safe, "LOCAL_PROJECT", id == null ? "-" : id.toString());
        return new LocalProjectAccessException("PROJECT_UNAVAILABLE", errorId, 503, "本地项目服务暂不可用，请凭错误编号检查配置或联系管理员");
    }

    /** @param principal 认证主体 @param id 已授权资源编号 @param failure 未预期异常 @return 已记录同号安全诊断的API失败，不复制路径/原始原因 */
    public LocalProjectAccessException infrastructureForApi(PrincipalRef principal,UUID id,Throwable failure) { return infrastructure(principal,id,"PROJECT_PANEL",failure); }

    /**
     * @param principal 可信当前主体
     * @param project 已经owner/RBAC/节点核验的活动项目
     * @param root 仅供同包执行器使用的实时根
     * @param binding 可选的长期Run绑定；元数据读取时为空
     */
    record Scope(PrincipalRef principal, LocalProject project, LocalProjectDirectoryTickets.Root root, LocalProjectBinding binding) {
        @Override public String toString() { return "Scope[受保护的项目访问]"; }
    }
}
