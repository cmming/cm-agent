package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.LocalProjectPageRequest;
import com.cmagent.server.config.LocalProjectProperties;
import com.cmagent.server.runtime.*;
import com.cmagent.server.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

/** 本机私有项目入口；只接不透明选择票据和根内相对路径，身份来自已校验JWT。 */
@RestController
@RequestMapping("/api/projects")
public final class LocalProjectController {
    /** 关闭模式不要求仓储/原生组件存在。 */ private final ObjectProvider<LocalProjectService> services;
    /** 原生选择线程只有明确启用时才装配。 */ private final ObjectProvider<LocalProjectSelections> selections;
    /** 请求本机来源检查，不能用任意Host推断可信源。 */ private final ObjectProvider<LocalProjectRequestBoundary> boundaries;
    /** 权限/安全审计与同号错误边界。 */ private final ObjectProvider<LocalProjectAccessService> access;
    /** 能力开关不泄露私有目录和执行节点。 */ private final LocalProjectProperties policy;
    /** 工具和命令仅在显式启用时装配，关闭模式不触发原生初始化。 */ private ObjectProvider<LocalProjectTools> projectTools;
    /** 固定工具目录不自动授予Agent。 */ private ObjectProvider<LocalProjectToolCatalog> catalogs;
    /** 只按已配置的工具链声明命令/构建可用性。 */ private ObjectProvider<com.cmagent.server.config.LocalProjectCommandProperties> commandPolicies;
    @org.springframework.beans.factory.annotation.Autowired
    void configureTools(ObjectProvider<LocalProjectTools> tools,ObjectProvider<LocalProjectToolCatalog> catalogs,
            ObjectProvider<com.cmagent.server.config.LocalProjectCommandProperties> commands) {
        this.projectTools=tools;this.catalogs=catalogs;this.commandPolicies=commands;
    }
    /** 项目专用同步审批复用原会话与恢复生命周期，不创建第二套批准逻辑。 */ private ObjectProvider<ToolApprovalService> approvalServices;
    @org.springframework.beans.factory.annotation.Autowired
    void configureApprovals(ObjectProvider<ToolApprovalService> provider){approvalServices=provider;}
    /** 操作历史不解密正文，人工核对也不授予任何重新派发权。 */ private ObjectProvider<LocalProjectOperationMaintenance> maintenance;
    @org.springframework.beans.factory.annotation.Autowired
    void configureMaintenance(ObjectProvider<LocalProjectOperationMaintenance> provider){maintenance=provider;}
    /** 关闭功能时可缺省；归档仅更新元数据，不持有目录删除或进程终止能力。 */ private ObjectProvider<LocalProjectArchiveService> archiveServices;
    @org.springframework.beans.factory.annotation.Autowired
    void optionalArchiveProvider(ObjectProvider<LocalProjectArchiveService> provider){archiveServices=provider;}

    public LocalProjectController(ObjectProvider<LocalProjectService> services, ObjectProvider<LocalProjectSelections> selections,
            ObjectProvider<LocalProjectRequestBoundary> boundaries, ObjectProvider<LocalProjectAccessService> access, LocalProjectProperties policy) {
        this.services = services; this.selections = selections; this.boundaries = boundaries; this.access = access; this.policy = policy;
    }

    /** 能力发现仅显示已装配功能；副作用/运行链路未装配前不能显示可执行。 */
    @GetMapping("/capabilities")
    public Capabilities capabilities(Authentication authentication, HttpServletRequest request) {
        PrincipalRef principal = principal(authentication);
        if (!policy.isEnabled()) return new Capabilities(false, true, false, false, false, false);
        guard(principal, request, null);
        access.getObject().authorize(principal, "project:read", null);
        boolean development=projectTools!=null&&projectTools.getIfAvailable()!=null;
        var commandPolicy=commandPolicies==null?null:commandPolicies.getIfAvailable();
        boolean commands=development&&commandPolicy!=null&&!commandPolicy.getTools().isEmpty();
        boolean builds=commands&&commandPolicy.getTools().values().stream().anyMatch(com.cmagent.server.config.LocalProjectCommandProperties.Tool::isBuild);
        return new Capabilities(true, !development, development, development, commands, builds);
    }

    @GetMapping
    public List<LocalProjectService.View> list(Authentication authentication, HttpServletRequest request,
            @RequestParam(name = "limit", defaultValue = "50") int limit, @RequestParam(name = "beforeUpdatedAt", required = false) Instant beforeUpdatedAt,
            @RequestParam(name = "beforeId", required = false) UUID beforeId, @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        var principal = principal(authentication); guard(principal, request, null);
        return services.getObject().list(principal, new LocalProjectPageRequest(limit, beforeUpdatedAt, beforeId), includeArchived);
    }

    @PostMapping("/selections")
    public ResponseEntity<LocalProjectSelections.View> select(Authentication authentication, HttpServletRequest request, @Valid @RequestBody Selection body) {
        var principal = principal(authentication); guard(principal, request, body.requestId());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(selections.getObject().start(principal, body.requestId()));
    }
    @GetMapping("/selections/{requestId}")
    public ResponseEntity<LocalProjectSelections.View> selection(Authentication authentication, HttpServletRequest request, @PathVariable("requestId") UUID requestId) {
        var principal = principal(authentication); guard(principal, request, requestId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(selections.getObject().get(principal, requestId));
    }
    @PostMapping
    public ResponseEntity<LocalProjectService.View> create(Authentication authentication, HttpServletRequest request, @Valid @RequestBody Create body) {
        var principal = principal(authentication); guard(principal, request, body.requestId());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(services.getObject().create(principal, body.requestId(), body.ticketId(), body.name()));
    }
    @GetMapping("/{projectId}")
    public LocalProjectService.View get(Authentication authentication, HttpServletRequest request, @PathVariable("projectId") UUID projectId) {
        var principal = principal(authentication); guard(principal, request, projectId);
        return services.getObject().get(principal, projectId);
    }
    @PatchMapping("/{projectId}")
    public LocalProjectService.View rename(Authentication authentication, HttpServletRequest request, @PathVariable("projectId") UUID projectId, @Valid @RequestBody Rename body) {
        var principal = principal(authentication); guard(principal, request, projectId);
        return services.getObject().rename(principal, projectId, body.expectedVersion(), body.name());
    }
    @PostMapping("/{projectId}/conversations")
    public ResponseEntity<Conversation> conversation(Authentication authentication, HttpServletRequest request, @PathVariable("projectId") UUID projectId, @Valid @RequestBody NewConversation body) {
        var principal = principal(authentication); guard(principal, request, projectId);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(services.getObject().createConversation(principal, projectId, body.agentId()));
    }
    /** 项目行锁下条件归档，保留原目录与历史，拒绝仍有活动或未知副作用的项目。 */
    @PostMapping("/{projectId}/archive")
    public ResponseEntity<LocalProjectService.View> archive(Authentication authentication, HttpServletRequest request,
            @PathVariable("projectId") UUID projectId, @Valid @RequestBody Archive body) {
        var principal = principal(authentication); guard(principal, request, projectId);
        if (archiveServices == null || archiveServices.getIfAvailable() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目归档尚未装配");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(archiveServices.getObject().archive(principal, projectId, body.expectedVersion()));
    }
    @GetMapping("/{projectId}/conversations")
    public ResponseEntity<List<Conversation>> conversations(Authentication authentication, HttpServletRequest request, @PathVariable("projectId") UUID projectId,
            @RequestParam(name = "limit", defaultValue = "50") int limit, @RequestParam(name = "beforeUpdatedAt", required = false) Instant beforeUpdatedAt,
            @RequestParam(name = "beforeId", required = false) UUID beforeId) {
        var principal = principal(authentication); guard(principal, request, projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(services.getObject().listConversations(principal, projectId,
                new com.cmagent.core.domain.ConversationPageRequest(limit, beforeUpdatedAt, beforeId)));
    }
    @GetMapping("/{projectId}/files")
    public ResponseEntity<LocalProjectService.Tree> tree(Authentication authentication, HttpServletRequest request, @PathVariable("projectId") UUID projectId,
            @RequestParam(name = "path", defaultValue = "") String path, @RequestParam(name = "cursor", required = false) String cursor, @RequestParam(name = "limit", defaultValue = "100") int limit) {
        var principal = principal(authentication); guard(principal, request, projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(services.getObject().tree(principal, projectId, path, cursor, limit));
    }
    @GetMapping("/{projectId}/text")
    public ResponseEntity<LocalProjectService.Text> text(Authentication authentication, HttpServletRequest request, @PathVariable("projectId") UUID projectId,
            @RequestParam("path") String path, @RequestParam(name = "offset", defaultValue = "0") int offset, @RequestParam(name = "maxBytes", defaultValue = "65536") int maxBytes) {
        var principal = principal(authentication); guard(principal, request, projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(services.getObject().read(principal, projectId, path, offset, maxBytes));
    }
    @GetMapping("/{projectId}/search")
    public ResponseEntity<LocalProjectService.Search> search(Authentication authentication, HttpServletRequest request, @PathVariable("projectId") UUID projectId,
            @RequestParam("query") String query, @RequestParam(name = "content", defaultValue = "false") boolean content) {
        var principal = principal(authentication); guard(principal, request, projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(services.getObject().search(principal, projectId, query, content));
    }

    /** 项目原owner在本机面板提交全量决定；仅新增同步交付方式，仍走同一审批CAS和Runtime恢复。 */
    @PostMapping("/{projectId}/conversations/{conversationId}/approvals/{approvalId}/decision")
    public ResponseEntity<ToolApprovalService.ApprovalResumeOutcome> decide(Authentication authentication,HttpServletRequest request,
            @PathVariable("projectId") UUID projectId,@PathVariable("conversationId") UUID conversationId,@PathVariable("approvalId") UUID approvalId,
            @Valid @RequestBody ApprovalDecision body) {
        var principal=principal(authentication);guard(principal,request,projectId);
        access.getObject().authorize(principal,"agent:approve",approvalId);access.getObject().authorize(principal,"agent:run",approvalId);
        var conversation=services.getObject().conversation(principal,projectId,conversationId);
        var decisions=body.decisions().stream().map(value->new ToolApprovalService.ItemDecision(value.itemId(),value.decision())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(approvalServices.getObject().decideAndResume(principal,conversation.agentId(),conversationId,
                approvalId,body.expectedVersion(),decisions,ignored->{},ignored->{},ignored->{}));
    }

    /** 显式登记固定目录；之后仍须在现有工具授权页面单独授予Agent。 */
    @PostMapping("/tools")
    public Object installTools(Authentication authentication,HttpServletRequest request) {
        var principal=principal(authentication);guard(principal,request,null);
        if(catalogs==null||catalogs.getIfAvailable()==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"项目工具尚未装配");
        return catalogs.getObject().install(principal);
    }
    /** 完整差异只交原owner，不进入普通会话轨迹或工具摘要。 */
    @GetMapping("/{projectId}/runs/{runId}/changes/{operationId}")
    public ResponseEntity<?> changePreview(Authentication authentication,HttpServletRequest request,@PathVariable("projectId") UUID projectId,
            @PathVariable("runId") UUID runId,@PathVariable("operationId") UUID operationId) {
        var principal=principal(authentication);guard(principal,request,projectId);
        return controlled(principal,runId,()->projectTools.getObject().changesPreview(principal,projectId,runId,operationId));
    }
    /** 命令清单和stdout/stderr只在项目面板受控查看，始终禁止HTTP缓存。 */
    @GetMapping("/{projectId}/runs/{runId}/commands/{operationId}")
    public ResponseEntity<?> command(Authentication authentication,HttpServletRequest request,@PathVariable("projectId") UUID projectId,
            @PathVariable("runId") UUID runId,@PathVariable("operationId") UUID operationId,@RequestParam(name="preview",defaultValue="false") boolean preview) {
        var principal=principal(authentication);guard(principal,request,projectId);
        return controlled(principal,runId,()->preview?projectTools.getObject().commandPreview(principal,projectId,runId,operationId):projectTools.getObject().commandStatus(principal,projectId,runId,operationId));
    }
    @PostMapping("/{projectId}/runs/{runId}/commands/{operationId}/cancel")
    public ResponseEntity<?> cancelCommand(Authentication authentication,HttpServletRequest request,@PathVariable("projectId") UUID projectId,
            @PathVariable("runId") UUID runId,@PathVariable("operationId") UUID operationId) {
        var principal=principal(authentication);guard(principal,request,projectId);
        return controlled(principal,runId,()->projectTools.getObject().cancelCommand(principal,projectId,runId,operationId));
    }
    private ResponseEntity<?> controlled(PrincipalRef principal,UUID id,java.util.concurrent.Callable<Object> action) {
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(action.call()); }
        catch(LocalProjectAccessException failure){throw failure;}
        catch(Exception failure){throw access.getObject().infrastructureForApi(principal,id,failure);}
    }

    @GetMapping("/{projectId}/runs/{runId}/operations")
    public ResponseEntity<List<LocalProjectOperationMaintenance.View>> operations(Authentication authentication,HttpServletRequest request,
            @PathVariable("projectId") UUID projectId,@PathVariable("runId") UUID runId,@RequestParam(name="limit",defaultValue="100") int limit) {
        var principal=principal(authentication);guard(principal,request,projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(maintenance.getObject().listRun(principal,projectId,runId,limit));
    }
    /** 明确接受未知副作用后只终结原意图，不能把这个确认字段作为HIGH审批或重试原命令。 */
    @PostMapping("/{projectId}/runs/{runId}/operations/{operationId}/resolve-unknown")
    public ResponseEntity<LocalProjectOperationMaintenance.View> resolveUnknown(Authentication authentication,HttpServletRequest request,
            @PathVariable("projectId") UUID projectId,@PathVariable("runId") UUID runId,@PathVariable("operationId") UUID operationId,
            @Valid @RequestBody ResolveUnknown body) {
        var principal=principal(authentication);guard(principal,request,projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(maintenance.getObject().resolveUnknown(principal,projectId,runId,operationId,body.expectedVersion(),body.acknowledgeExternalEffects()));
    }

    private void guard(PrincipalRef principal, HttpServletRequest request, UUID resourceId) {
        if (!policy.isEnabled() || boundaries.getIfAvailable() == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "本地项目功能未启用");
        if (!boundaries.getObject().permits(request))
            throw access.getObject().reject(principal, resourceId, "PROJECT_LOCAL_REQUEST_REQUIRED", 403, "请从已配置的本机控制台执行项目操作");
    }
    private static PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof JwtService.JwtSession session))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(), Set.copyOf(session.permissions()));
    }

    /** 服务已记录失败边界，HTTP层只返回同一编号，不再次打印潜在正文。 */
    @ExceptionHandler(LocalProjectAccessException.class)
    public ResponseEntity<Failure> failure(LocalProjectAccessException failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
                .body(new Failure(failure.errorCode(), failure.errorId(), failure.getMessage()));
    }

    /** @param enabled 当前部署是否已启用 @param readOnly 是否只有读取入口 @param conversations 是否可从项目运行会话 @param changes 是否已装配治理变更 @param commands 是否已装配治理命令 @param builds 是否已装配治理构建 */
    public record Capabilities(boolean enabled, boolean readOnly, boolean conversations, boolean changes, boolean commands, boolean builds) {}
    /** @param requestId 当前明确选择动作的随机编号 */
    public record Selection(@NotNull UUID requestId) {}
    /** @param requestId 原明确选择动作编号 @param ticketId 同主体原生票据 @param name 项目展示名，不解释为路径 */
    public record Create(@NotNull UUID requestId, @NotNull UUID ticketId, @NotBlank @Size(max = 120) String name) {}
    /** @param expectedVersion 读取时的元数据版本 @param name 新展示名 */
    public record Rename(@Min(1) long expectedVersion, @NotBlank @Size(max = 120) String name) {}
    /** @param expectedVersion 当前读取的项目元数据版本，不能绕过活动/未知操作检查 */
    public record Archive(@Min(1) long expectedVersion) {}
    /** @param agentId 当前租户内的固定Agent */
    public record NewConversation(@NotNull UUID agentId) {}
    /** @param expectedVersion 页面读取的审批版本 @param decisions 全部明细的逐项明确决定，不能用一项覆盖整批 */
    public record ApprovalDecision(@Min(0) long expectedVersion,@NotEmpty @Size(max=100) List<@Valid ApprovalItem> decisions) {
        public ApprovalDecision{decisions=decisions==null?null:List.copyOf(decisions);}
    }
    /** @param itemId 原审批明细编号 @param decision 明确允许或拒绝，不从预选框推断用户意图 */
    public record ApprovalItem(@NotNull UUID itemId,@NotNull com.cmagent.core.domain.ToolApprovalDecision decision) {}
    /** @param expectedVersion 最新读取的UNKNOWN版本 @param acknowledgeExternalEffects 原主体已人工核对并接受可能已有副作用，不表示批准执行 */
    public record ResolveUnknown(@Min(1) long expectedVersion,boolean acknowledgeExternalEffects) {}
    /** @param errorCode 稳定错误分类 @param errorId 与后台相同的随机编号 @param message 安全中文原因 */
    public record Failure(String errorCode, String errorId, String message) {}
}
