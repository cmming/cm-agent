package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.LocalClientPairingService;
import com.cmagent.server.runtime.LocalClientProofService;
import com.cmagent.server.runtime.LocalClientOperationService;
import com.cmagent.server.runtime.ClientProjectService;
import com.cmagent.server.runtime.ClientProjectFenceService;
import com.cmagent.server.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 浏览器配对确认、设备管理及本机客户端匿名引导端点。 */
@RestController
@RequestMapping("/api/local-client")
@ConditionalOnProperty(prefix = "cm-agent.local-client", name = "enabled", havingValue = "true")
public class LocalClientController {
    /** 客户端设备标识请求头。 */
    static final String DEVICE_HEADER = "X-CM-Device-Id";
    /** 服务端短期一次挑战标识请求头。 */
    static final String CHALLENGE_HEADER = "X-CM-Challenge-Id";
    /** 对实际方法和路径绑定的 Ed25519 签名请求头。 */
    static final String SIGNATURE_HEADER = "X-CM-Signature";
    /** 协议 5 项目任务显式版本头，旧客户端不能把专用任务误当成通用设备操作。 */
    static final String PROJECT_PROTOCOL_HEADER = "X-CM-Client-Project-Protocol";
    private final LocalClientPairingService pairings;
    private final LocalClientProofService proofs;
    private final LocalClientOperationService operations;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender audit;
    /** 远端设备项目未启用时为空；普通设备任务不依赖该组件。 */
    private final org.springframework.beans.factory.ObjectProvider<ClientProjectService> clientProjects;
    private org.springframework.beans.factory.ObjectProvider<ClientProjectFenceService> fenceServices;

    /** 握手服务仅在设备项目启用时存在；未装配时不会退回普通设备通道。 */
    @org.springframework.beans.factory.annotation.Autowired
    void configureFenceServices(org.springframework.beans.factory.ObjectProvider<ClientProjectFenceService> provider) {
        fenceServices = provider;
    }

    /** 兼容仅验证普通设备合同的既有测试；生产装配使用包含可选项目服务的构造器。 */
    public LocalClientController(LocalClientPairingService pairings, LocalClientProofService proofs,
            LocalClientOperationService operations,
            PermissionEvaluator permissionEvaluator, AuditAppender audit) {
        this(pairings, proofs, operations, permissionEvaluator, audit, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LocalClientController(LocalClientPairingService pairings, LocalClientProofService proofs,
            LocalClientOperationService operations,
            PermissionEvaluator permissionEvaluator, AuditAppender audit,
            org.springframework.beans.factory.ObjectProvider<ClientProjectService> clientProjects) {
        this.pairings = pairings;
        this.proofs = proofs;
        this.operations = operations;
        this.permissionEvaluator = permissionEvaluator;
        this.audit = audit;
        this.clientProjects = clientProjects;
    }

    /** 登记浏览器转交的已签名配对包，需要运行权限。 */
    @PostMapping("/pairings")
    public LocalClientPairingService.PairingView begin(
            @Valid @RequestBody PairingRequest request, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:run", "LOCAL_CLIENT_PAIRING", request.requestId().toString());
        return pairings.begin(principal, new LocalClientPairingService.PairingPackage(
                request.requestId(), decode(request.nonce(), 32), request.deviceName(),
                request.platform(), request.publicKey(), decode(request.proof(), 64)));
    }

    /** 用户明确确认配对并创建设备记录，需要运行权限。编译产物未保留方法参数名，路径变量必须显式命名。 */
    @PostMapping("/pairings/{pairingId}/confirm")
    public LocalClientPairingService.PairingView confirm(
            @PathVariable("pairingId") UUID pairingId, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:run", "LOCAL_CLIENT_PAIRING", pairingId.toString());
        return pairings.confirm(principal, pairingId);
    }

    /** 列出当前主体设备及撤销状态。 */
    @GetMapping("/devices")
    public List<LocalClientPairingService.DeviceView> devices(Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", "LOCAL_CLIENT_DEVICE", "list");
        return pairings.devices(principal);
    }

    /** 条件撤销当前主体设备；已派发的外部副作用不会回滚。 */
    @PostMapping("/devices/{deviceId}/revoke")
    public LocalClientPairingService.DeviceView revoke(
            @PathVariable("deviceId") UUID deviceId, @Valid @RequestBody RevokeRequest request,
            Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:run", "LOCAL_CLIENT_DEVICE", deviceId.toString());
        return pairings.revoke(principal, deviceId, request.expectedVersion());
    }

    /** 未登录客户端轮询非秘密配对状态；随机标识不能替代浏览器认证确认。 */
    @GetMapping("/bootstrap/pairings/{pairingId}")
    public LocalClientPairingService.PairingView pairingStatus(@PathVariable("pairingId") UUID pairingId) {
        return pairings.status(pairingId);
    }

    /** 未登录但已持钥的客户端申请一次性签名挑战。 */
    @PostMapping("/bootstrap/challenges")
    public ResponseEntity<LocalClientProofService.ChallengeView> challenge(
            @Valid @RequestBody ChallengeRequest request, HttpServletRequest servletRequest) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(proofs.challenge(request.deviceId(), servletRequest.getRemoteAddr()));
    }

    /** 签名客户端领取一条有界任务；没有任务返回 204，挑战仍已被单次消费。 */
    @PostMapping(value = "/bootstrap/operations/next", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LocalClientOperationService.TaskView> nextOperation(
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        LocalClientDevice device = verifiedDevice(body, request);
        return operations.claim(device).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 签名客户端回报任务成功、失败或结果未知。 */
    @PostMapping(value = "/bootstrap/operations/{operationId}/result",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> operationResult(@PathVariable("operationId") UUID operationId,
            @RequestBody byte[] body, HttpServletRequest request) {
        LocalClientDevice device = verifiedDevice(body, request);
        operations.complete(device, operationId, body);
        return ResponseEntity.noContent().build();
    }

    /** 签名设备领取远端项目任务；功能未装配时明确返回 404。 */
    @PostMapping(value = "/bootstrap/project-tasks/next",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> nextProjectTask(
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        requireProjectProtocol(request);
        LocalClientDevice device = verifiedDevice(body, request);
        // 能力声明必须包含在已验签正文中；未签名的请求头不能证明旧客户端会执行本地确认和超时。
        if (!java.util.Arrays.equals(body, "{\"selectionProtocol\":1,\"fenceProtocol\":1}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UPGRADE_REQUIRED,
                    "请升级本机客户端以支持目录选择确认、超时和运行代次握手");
        }
        ClientProjectService service = clientProjects == null ? null : clientProjects.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "已配对设备项目功能未启用");
        }
        ClientProjectService.ClaimDecision decision = service.claim(device);
        if (decision.task() != null) return ResponseEntity.ok(decision.task());
        if (decision.fence() != null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .cacheControl(org.springframework.http.CacheControl.noStore())
                    .body(decision.fence());
        }
        return ResponseEntity.noContent().build();
    }

    /** 签名设备回报远端项目任务结果。 */
    @PostMapping(value = "/bootstrap/project-tasks/{taskId}/result",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> projectTaskResult(@PathVariable("taskId") UUID taskId,
            @RequestBody byte[] body, HttpServletRequest request) {
        requireProjectProtocol(request);
        LocalClientDevice device = verifiedDevice(body, request);
        ClientProjectService service = clientProjects == null ? null : clientProjects.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "已配对设备项目功能未启用");
        }
        service.complete(device, taskId, body);
        return ResponseEntity.noContent().build();
    }

    /** 仅接受已签名的严格代次正文；主体和设备来自验签结果，不采用正文提供的身份。 */
    @PostMapping(value = "/bootstrap/project-runs/{runId}/fence-ack", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> acknowledgeProjectFence(@PathVariable("runId") UUID runId,
            @RequestBody byte[] body, HttpServletRequest request) {
        requireProjectProtocol(request);
        LocalClientDevice device = verifiedDevice(body, request);
        long epoch = fenceEpoch(body);
        ClientProjectFenceService service = fenceServices == null ? null : fenceServices.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备项目代次功能未启用");
        service.acknowledge(device, runId, epoch);
        return ResponseEntity.noContent().cacheControl(org.springframework.http.CacheControl.noStore()).build();
    }

    /** 仅已签名声明支持新握手的设备可读取当前代次；响应不得缓存。 */
    @PostMapping(value = "/bootstrap/project-runs/{runId}/fence-current", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClientProjectFenceService.FenceView> currentProjectFence(
            @PathVariable("runId") UUID runId, @RequestBody byte[] body, HttpServletRequest request) {
        requireProjectProtocol(request);
        LocalClientDevice device = verifiedDevice(body, request);
        if (!java.util.Arrays.equals(body, "{\"fenceProtocol\":1}".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new ResponseStatusException(HttpStatus.UPGRADE_REQUIRED, "请升级本机客户端以支持运行代次握手");
        ClientProjectFenceService service = fenceServices == null ? null : fenceServices.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备项目代次功能未启用");
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(service.current(device, runId));
    }

    private static long fenceEpoch(byte[] body) {
        if (body == null || body.length > 32) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备代次请求无效");
        String value = new String(body, java.nio.charset.StandardCharsets.US_ASCII);
        if (!value.matches("\\{\\\"epoch\\\":[1-9][0-9]{0,18}\\}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备代次请求无效");
        try {
            return Long.parseLong(value.substring(9, value.length() - 1));
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备代次请求无效");
        }
    }

    private LocalClientDevice verifiedDevice(byte[] body, HttpServletRequest request) {
        UUID deviceId = headerUuid(request, DEVICE_HEADER);
        UUID challengeId = headerUuid(request, CHALLENGE_HEADER);
        return proofs.verifyAndConsume(deviceId, challengeId, request.getHeader(SIGNATURE_HEADER),
                request.getMethod(), request.getRequestURI(), body, request.getRemoteAddr());
    }

    private static UUID headerUuid(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "设备签名请求头无效");
        }
    }

    private static void requireProjectProtocol(HttpServletRequest request) {
        if (!Integer.toString(com.cmagent.core.domain.ClientProjectBinding.PROTOCOL)
                .equals(request.getHeader(PROJECT_PROTOCOL_HEADER))) {
            throw new ResponseStatusException(HttpStatus.UPGRADE_REQUIRED,
                    "本机客户端版本过旧，请升级到支持设备项目协议 5 的版本");
        }
    }

    private static byte[] decode(String base64, int length) {
        try {
            byte[] value = Base64.getDecoder().decode(base64);
            if (value.length != length) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配对包编码无效");
        }
    }

    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(),
                Set.copyOf(session.permissions()));
    }

    private void authorize(PrincipalRef principal, String permission, String type, String id) {
        AuthorizationDecision decision = permissionEvaluator.check(principal, permission);
        if (!decision.allowed()) {
            audit.accessDenied(principal, type, id, permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    /**
     * 浏览器提交的客户端配对包。
     *
     * @param requestId 客户端随机请求标识
     * @param nonce 随机值 Base64 编码
     * @param deviceName 设备名称
     * @param platform 平台
     * @param publicKey X.509 公钥 Base64 编码
     * @param proof 签名 Base64 编码
     */
    public record PairingRequest(
            @NotNull UUID requestId,
            @NotBlank String nonce,
            @NotBlank String deviceName,
            @NotNull LocalClientDevice.Platform platform,
            @NotBlank String publicKey,
            @NotBlank String proof
    ) { }

    /** @param expectedVersion 浏览器看到的设备版本 */
    public record RevokeRequest(@PositiveOrZero long expectedVersion) { }

    /** @param deviceId 客户端已配对设备标识 */
    public record ChallengeRequest(@NotNull UUID deviceId) { }
}
