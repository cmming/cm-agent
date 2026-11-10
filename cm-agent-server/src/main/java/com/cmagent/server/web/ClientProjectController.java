package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ClientProjectTask;
import com.cmagent.core.domain.LocalProjectPageRequest;
import com.cmagent.server.config.ClientProjectProperties;
import com.cmagent.server.runtime.ClientProjectRequestBoundary;
import com.cmagent.server.runtime.ClientProjectService;
import com.cmagent.server.runtime.ClientProjectRunService;
import com.cmagent.server.runtime.ClientProjectCommandService;
import com.cmagent.server.runtime.LocalProjectAccessException;
import com.cmagent.server.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 已配对设备项目的浏览器入口；不接收客户端绝对路径。 */
@RestController
@RequestMapping("/api/client-projects")
public final class ClientProjectController {
    private final ClientProjectProperties properties;
    private final ObjectProvider<ClientProjectService> services;
    private final ObjectProvider<ClientProjectRequestBoundary> boundaries;
    private final ObjectProvider<ClientProjectRunService> runs;
    private final ObjectProvider<ClientProjectCommandService> commands;

    public ClientProjectController(ClientProjectProperties properties,
            ObjectProvider<ClientProjectService> services,
            ObjectProvider<ClientProjectRequestBoundary> boundaries,
            ObjectProvider<ClientProjectRunService> runs,
            ObjectProvider<ClientProjectCommandService> commands) {
        this.properties = properties;
        this.services = services;
        this.boundaries = boundaries;
        this.runs = runs;
        this.commands = commands;
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities(Authentication authentication, HttpServletRequest request) {
        PrincipalRef principal = principal(authentication);
        if (!properties.isEnabled()) return new Capabilities(false, false, false, false, false);
        guard(principal, request, null);
        return new Capabilities(true, true, true, true, true);
    }

    @PostMapping("/selections")
    public ResponseEntity<ClientProjectService.TaskView> select(Authentication authentication,
            HttpServletRequest request, @Valid @RequestBody Selection body) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, body.requestId());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(service().startSelection(principal, body.requestId(),
                        body.deviceId(), body.suggestedName()));
    }

    @GetMapping("/selections/{requestId}")
    public ResponseEntity<ClientProjectService.TaskStatusView> selection(
            Authentication authentication, HttpServletRequest request,
            @PathVariable("requestId") UUID requestId) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, requestId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service().taskStatus(principal, requestId));
    }

    @GetMapping
    public ResponseEntity<List<ClientProjectService.ProjectView>> list(
            Authentication authentication, HttpServletRequest request,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "beforeUpdatedAt", required = false) Instant beforeUpdatedAt,
            @RequestParam(name = "beforeId", required = false) UUID beforeId,
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service().list(principal,
                        new LocalProjectPageRequest(limit, beforeUpdatedAt, beforeId),
                        includeArchived));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ClientProjectService.ProjectView> project(
            Authentication authentication, HttpServletRequest request,
            @PathVariable("projectId") UUID projectId) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service().project(principal, projectId));
    }

    @PatchMapping("/{projectId}")
    public ResponseEntity<ClientProjectService.ProjectView> rename(
            Authentication authentication, HttpServletRequest request,
            @PathVariable("projectId") UUID projectId, @Valid @RequestBody Rename body) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service().rename(principal, projectId, body.expectedVersion(), body.name()));
    }

    @PostMapping("/{projectId}/archive")
    public ResponseEntity<ClientProjectService.ProjectView> archive(
            Authentication authentication, HttpServletRequest request,
            @PathVariable("projectId") UUID projectId, @Valid @RequestBody Archive body) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service().archive(principal, projectId, body.expectedVersion()));
    }

    @PostMapping("/{projectId}/queries")
    public ResponseEntity<ClientProjectService.TaskView> query(Authentication authentication,
            HttpServletRequest request, @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody Query body) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, body.requestId());
        ClientProjectTask.Kind kind;
        try {
            kind = ClientProjectTask.Kind.valueOf(body.kind());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备项目查询种类无效");
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(service().startQuery(principal, projectId, body.requestId(), kind,
                        new ClientProjectService.ReadRequest(body.path(), body.cursor(), body.limit(),
                                body.offset(), body.maxBytes(), body.query(), body.content())));
    }

    @PostMapping("/{projectId}/conversations")
    public ResponseEntity<com.cmagent.core.domain.Conversation> conversation(
            Authentication authentication, HttpServletRequest request,
            @PathVariable("projectId") UUID projectId, @Valid @RequestBody NewConversation body) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, projectId);
        ClientProjectRunService runService = runs.getIfAvailable();
        if (runService == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备项目会话未装配");
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(runService.createConversation(principal, projectId, body.agentId(),
                        body.requestId()));
    }

    @GetMapping("/{projectId}/conversations")
    public ResponseEntity<List<com.cmagent.core.domain.Conversation>> conversations(
            Authentication authentication, HttpServletRequest request,
            @PathVariable("projectId") UUID projectId,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "beforeUpdatedAt", required = false) Instant beforeUpdatedAt,
            @RequestParam(name = "beforeId", required = false) UUID beforeId) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, projectId);
        ClientProjectRunService runService = runs.getIfAvailable();
        if (runService == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备项目会话未装配");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(runService.listConversations(principal, projectId,
                        new com.cmagent.core.domain.ConversationPageRequest(limit,
                                beforeUpdatedAt, beforeId)));
    }

    @GetMapping("/queries/{requestId}")
    public ResponseEntity<ClientProjectService.TaskStatusView> queryStatus(
            Authentication authentication, HttpServletRequest request,
            @PathVariable("requestId") UUID requestId) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, requestId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service().taskStatus(principal, requestId));
    }

    /** 原主体读取某个设备项目变更的脱敏精确预览，不返回原始文件身份。 */
    @GetMapping("/{projectId}/changes/{operationId}")
    public ResponseEntity<ClientProjectService.ChangePreview> changePreview(
            Authentication authentication, HttpServletRequest request,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("operationId") UUID operationId) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, operationId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service().changePreview(principal, projectId, operationId));
    }

    /** 原主体读取设备项目变更安全状态。 */
    @GetMapping("/{projectId}/operations/{operationId}")
    public ResponseEntity<ClientProjectService.OperationView> operation(
            Authentication authentication, HttpServletRequest request,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("operationId") UUID operationId) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, operationId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service().operation(principal, projectId, operationId));
    }

    /** 读取固定工具链命令的权威状态和最近脱敏输出。 */
    @GetMapping("/{projectId}/commands/{commandId}")
    public ResponseEntity<ClientProjectCommandService.CommandView> command(
            Authentication authentication, HttpServletRequest request,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("commandId") UUID commandId) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, commandId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(commandService().command(principal, projectId, commandId));
    }

    /** 读取命令精确审批预览，不返回宿主可执行文件路径或基础环境值。 */
    @GetMapping("/{projectId}/commands/{commandId}/preview")
    public ResponseEntity<ClientProjectCommandService.CommandPreview> commandPreview(
            Authentication authentication, HttpServletRequest request,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("commandId") UUID commandId) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, commandId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(commandService().preview(principal, projectId, commandId));
    }

    /** 请求一次设备状态快照并返回 202 任务句柄。 */
    @PostMapping("/{projectId}/commands/{commandId}/status")
    public ResponseEntity<ClientProjectService.TaskView> commandStatus(
            Authentication authentication, HttpServletRequest request,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("commandId") UUID commandId) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, commandId);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(commandService().startStatus(principal, projectId, commandId));
    }

    /** 请求停止指定命令；任务成功仅表示设备已受理，不表示进程已退出。 */
    @PostMapping("/{projectId}/commands/{commandId}/stop")
    public ResponseEntity<ClientProjectService.TaskView> commandStop(
            Authentication authentication, HttpServletRequest request,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("commandId") UUID commandId) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, commandId);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(commandService().startStop(principal, projectId, commandId));
    }

    /** 人工接受 UNKNOWN 外部副作用，但不释放根或重放命令。 */
    @PostMapping("/{projectId}/commands/{commandId}/resolve-unknown")
    public ResponseEntity<ClientProjectCommandService.CommandView> resolveCommandUnknown(
            Authentication authentication, HttpServletRequest request,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("commandId") UUID commandId,
            @Valid @RequestBody ResolveCommandUnknown body) {
        PrincipalRef principal = principal(authentication);
        guard(principal, request, commandId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(commandService().resolveUnknown(principal, projectId, commandId,
                        body.expectedVersion(), body.acknowledgeExternalEffects()));
    }

    private ClientProjectService service() {
        if (!properties.isEnabled() || services.getIfAvailable() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "已配对设备项目功能未启用");
        }
        return services.getObject();
    }

    private ClientProjectCommandService commandService() {
        if (!properties.isEnabled() || commands.getIfAvailable() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备项目命令功能未启用");
        }
        return commands.getObject();
    }

    private void guard(PrincipalRef principal, HttpServletRequest request, UUID resourceId) {
        if (!properties.isEnabled() || boundaries.getIfAvailable() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "已配对设备项目功能未启用");
        }
        if (!boundaries.getObject().permits(request)) {
            throw service().requestRejected(principal, resourceId);
        }
    }

    private static PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(),
                Set.copyOf(session.permissions()));
    }

    /** 功能能力；文件变更和命令均进入开关、来源与逐次审批门禁。 */
    public record Capabilities(boolean enabled, boolean readOnly, boolean conversations,
            boolean changes, boolean commands) { }

    /** 浏览器选择根请求。 */
    public record Selection(@NotNull UUID requestId, @NotNull UUID deviceId,
            @Size(max = 120) String suggestedName) { }

    /** 项目重命名请求。 */
    public record Rename(@Min(1) long expectedVersion,
            @NotBlank @Size(max = 120) String name) { }

    /** 项目归档请求。 */
    public record Archive(@Min(1) long expectedVersion) { }

    /** 命令 UNKNOWN 的人工对账声明；只记录外部副作用认知，不解除根隔离。 */
    public record ResolveCommandUnknown(@Min(1) long expectedVersion,
            boolean acknowledgeExternalEffects) { }

    /** 固定项目只读查询。 */
    public record Query(@NotNull UUID requestId, @NotBlank String kind,
            @Size(max = 1024) String path, @Size(max = 1024) String cursor,
            @Min(1) @Max(200) int limit, @Min(0) int offset,
            @Min(4) @Max(65536) int maxBytes, @Size(max = 256) String query,
            boolean content) { }

    /** 新项目会话固定 Agent，具体工具由 Server 目录授予。 */
    public record NewConversation(@NotNull UUID requestId, @NotNull UUID agentId) { }
}
