package com.cmagent.server.service;

import com.cmagent.api.*;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.SandboxEndpointRepository;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.SkillProperties;
import com.cmagent.server.runtime.*;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * 编排端点短事务、认证密文、探测与默认选择；网络调用不在数据库事务中执行。
 */
@Service
public class SandboxEndpointService {
    /**
     * 租户仓储，不缓存默认端点以保证跨实例修改立即可见。
     */
    private final SandboxEndpointRepository repository;
    /**
     * 外部部署的安全上限。
     */
    private final SkillProperties properties;
    /**
     * 严格审计依赖。
     */
    private final AuditAppender audit;
    /**
     * 独立沙箱凭据加密器。
     */
    private final SandboxCredentialCipher cipher;
    /**
     * 控制面与执行面的同一目标策略。
     */
    private final SandboxTargetPolicy targets;
    /**
     * 探测并发限额，拒绝排队探测造成额外出站连接。
     */
    private final java.util.concurrent.Semaphore probes = new java.util.concurrent.Semaphore(2);

    /**
     * @param repository 租户仓储 @param properties 部署策略 @param audit 严格审计 @param cipher 加密器 @param targets 目标策略
     */
    public SandboxEndpointService(SandboxEndpointRepository repository, SkillProperties properties, AuditAppender audit,
                                  SandboxCredentialCipher cipher, SandboxTargetPolicy targets) {
        this.repository = repository;
        this.properties = properties;
        this.audit = audit;
        this.cipher = cipher;
        this.targets = targets;
    }

    /**
     * @return 部署开关
     */
    public boolean enabled() {
        return properties.getSandbox().isEnabled();
    }

    /**
     * @return 主密钥是否就绪，不返回内容
     */
    public boolean credentialReady() {
        return cipher.ready();
    }

    /**
     * @param tenant 可信租户 @return 该租户端点
     */
    public List<SandboxEndpoint> list(UUID tenant) {
        return repository.list(tenant);
    }

    /**
     * @param tenant 可信租户 @param id 端点 @return 同租户端点
     */
    public SandboxEndpoint get(UUID tenant, UUID id) {
        return repository.find(tenant, id).orElseThrow(() -> failure(ApiErrorCode.SKILL_SANDBOX_ENDPOINT_NOT_FOUND, "沙箱端点不存在"));
    }

    /**
     * @param tenant 可信租户 @return 默认标识
     */
    public Optional<UUID> defaultId(UUID tenant) {
        return repository.defaultId(tenant);
    }

    /**
     * @param principal 可信主体 @param name 显示名 @param backend 注册标识 @param mode 协议 @param host 主机 @param port 端口 @param username 用户 @param enabled 是否启用 @param credentials 写入材料 @return 新端点
     */
    public SandboxEndpoint create(PrincipalRef principal, String name, String backend, SandboxConnectionMode mode, String host, int port,
                                  String username, boolean enabled, SandboxCredentials credentials) {
        return create(principal, name, backend, mode, host, port, username, enabled, credentials, SandboxSshAuthType.KEY);
    }

    /**
     * @param principal 主体 @param name 名称 @param backend 后端 @param mode 协议 @param host 主机 @param port 端口 @param username 用户 @param enabled 启用 @param credentials 只写材料 @param auth SSH认证类型 @return 已保存端点
     */
    public SandboxEndpoint create(PrincipalRef principal, String name, String backend, SandboxConnectionMode mode, String host, int port,
                                  String username, boolean enabled, SandboxCredentials credentials, SandboxSshAuthType auth) {
        auth = auth == null ? SandboxSshAuthType.KEY : auth;
        writable();
        validate(name, backend, mode, host, port, username);
        validateCredentials(mode, credentials, auth);
        UUID id = UUID.randomUUID();
        long cv = mode == SandboxConnectionMode.LOCAL ? 0 : 1;
        String encrypted = cv == 0 ? "" : cipher.encrypt(principal.tenantId(), id, cv, credentials);
        Instant now = Instant.now();
        var endpoint = new SandboxEndpoint(id, principal.tenantId(), name, backend, mode, host, port, username, enabled, encrypted, cv, 1, 0, null, "NOT_TESTED", false, now, now, auth);
        return repository.atomic(principal.tenantId(), () -> {
            repository.insert(endpoint);
            event(principal, id, "CREATE");
            return endpoint;
        });
    }

    /**
     * 更新采用CAS且使旧探测失效；省略凭据仅在协议不变时保留。 @param principal 主体 @param id 端点 @param revision 旧版本 @param name 显示名 @param backend 后端 @param mode 协议 @param host 主机 @param port 端口 @param username 用户 @param enabled 启用 @param credentials 可选新材料 @return 新版本
     */
    public SandboxEndpoint update(PrincipalRef principal, UUID id, long revision, String name, String backend, SandboxConnectionMode mode,
                                  String host, int port, String username, boolean enabled, SandboxCredentials credentials) {
        return update(principal, id, revision, name, backend, mode, host, port, username, enabled, credentials, SandboxSshAuthType.KEY);
    }

    /**
     * @param principal 主体 @param id 端点 @param revision CAS版本 @param name 名称 @param backend 后端 @param mode 协议 @param host 主机 @param port 端口 @param username 用户 @param enabled 启用 @param credentials 完整新材料，整体省略仅同类型保留 @param auth SSH认证类型 @return 新版本
     */
    public SandboxEndpoint update(PrincipalRef principal, UUID id, long revision, String name, String backend, SandboxConnectionMode mode,
                                  String host, int port, String username, boolean enabled, SandboxCredentials credentials, SandboxSshAuthType auth) {
        SandboxSshAuthType selectedAuth = auth == null ? SandboxSshAuthType.KEY : auth;
        writable();
        validate(name, backend, mode, host, port, username);
        return repository.atomic(principal.tenantId(), () -> {
            var old = get(principal.tenantId(), id);
            if (old.revision() != revision) throw conflict();
            if ((mode != old.mode() || selectedAuth != old.sshAuthType()) && credentials == null && mode != SandboxConnectionMode.LOCAL)
                throw failure(ApiErrorCode.SKILL_SANDBOX_INVALID, "更改连接协议或SSH认证类型时必须重新配置完整凭据");
            if (mode != SandboxConnectionMode.SSH && selectedAuth != SandboxSshAuthType.KEY)
                throw failure(ApiErrorCode.SKILL_SANDBOX_INVALID, "账号密码认证只能用于SSH连接");
            long cv = old.credentialVersion();
            String encrypted = old.encryptedCredential();
            if (mode == SandboxConnectionMode.LOCAL) {
                cv = 0;
                encrypted = "";
            } else if (credentials != null) {
                validateCredentials(mode, credentials, selectedAuth);
                cv++;
                encrypted = cipher.encrypt(principal.tenantId(), id, cv, credentials);
            } else validateCredentials(mode, cipher.decrypt(old.tenantId(), id, cv, encrypted), selectedAuth);
            var next = new SandboxEndpoint(id, old.tenantId(), name, backend, mode, host, port, username, enabled, encrypted, cv, revision + 1,
                    0, null, "NOT_TESTED", false, old.createdAt(), Instant.now(), selectedAuth);
            if (!repository.update(next, revision)) throw conflict();
            event(principal, id, credentials == null ? "UPDATE" : "ROTATE_CREDENTIAL");
            return next;
        });
    }

    /**
     * @param principal 主体 @param id 端点 @param revision 旧版本
     */
    public void delete(PrincipalRef principal, UUID id, long revision) {
        writable();
        repository.atomic(principal.tenantId(), () -> {
            var old = get(principal.tenantId(), id);
            if (old.revision() != revision || repository.defaultId(principal.tenantId()).filter(id::equals).isPresent())
                throw failure(ApiErrorCode.SKILL_SANDBOX_ENDPOINT_CONFLICT, "默认端点不可删除，请先切换或取消默认");
            var next = copy(old, false, revision + 1, 0, old.probedAt(), old.probeStatus(), true);
            if (!repository.update(next, revision)) throw conflict();
            event(principal, id, "DELETE");
            return null;
        });
    }

    /**
     * @param principal 主体 @param id 默认端点，空表示取消 @param revision 当前端点版本
     */
    public void setDefault(PrincipalRef principal, UUID id, long revision) {
        writable();
        repository.atomic(principal.tenantId(), () -> {
            if (id != null) {
                var endpoint = get(principal.tenantId(), id);
                if (endpoint.revision() != revision || !endpoint.enabled() || endpoint.probeRevision() != revision)
                    throw failure(ApiErrorCode.SKILL_SANDBOX_ENDPOINT_CONFLICT, "仅能选择已启用且当前版本连接测试通过的端点");
                connection(endpoint);
            }
            repository.setDefault(principal.tenantId(), id);
            event(principal, id, "SET_DEFAULT");
            return null;
        });
    }

    /**
     * 探测固定保存版本，失败仍保存安全结果；成功不表示技能发布通过。 @param principal 主体 @param id 端点 @param revision 版本 @return 探测后的端点
     */
    public SandboxEndpoint probe(PrincipalRef principal, UUID id, long revision) {
        writable();
        var endpoint = get(principal.tenantId(), id);
        if (endpoint.revision() != revision) throw conflict();
        if (!probes.tryAcquire())
            throw failure(ApiErrorCode.SKILL_SANDBOX_LIMIT_EXCEEDED, "沙箱连接测试繁忙，请稍后重试");
        SkillAccessException controlled = null;
        try (var daemon = DockerSkillSandboxConnection.open(connection(endpoint))) {
            check(daemon, List.of("info", "--format", "{{.OSType}}|{{.MemoryLimit}}|{{.PidsLimit}}|{{.CPUCfsQuota}}"), "linux|true|true|true");
            check(daemon, List.of("image", "inspect", "--format", "{{.Id}}", properties.getSandbox().getImage()), null);
            String runtime = properties.getSandbox().getRuntime();
            if (!runtime.isEmpty()) check(daemon, List.of("info", "--format", "{{json .Runtimes}}"), runtime);
        } catch (SkillAccessException failure) {
            controlled = failure;
        } finally {
            if (controlled == null || controlled.code() != ApiErrorCode.SKILL_SANDBOX_CLEANUP_FAILED) probes.release();
        }
        var resultFailure = controlled;
        var result = repository.atomic(principal.tenantId(), () -> {
            var current = get(principal.tenantId(), id);
            if (current.revision() != revision) throw conflict();
            var next = copy(current, current.enabled(), revision, resultFailure == null ? revision : 0, Instant.now(), resultFailure == null ? "PASSED" : "FAILED", false);
            if (!repository.update(next, revision)) throw conflict();
            event(principal, id, resultFailure == null ? "PROBE_PASSED" : "PROBE_FAILED");
            return next;
        });
        if (controlled != null) throw controlled;
        return result;
    }

    private void check(DockerDaemonConnection daemon, List<String> args, String expected) {
        Process process = null;
        var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            process = daemon.start(args);
            Process running = process;
            var output = workers.submit(() -> {
                try (var in = running.getInputStream()) {
                    byte[] bytes = in.readNBytes(32769);
                    if (bytes.length > 32768)
                        throw failure(ApiErrorCode.SKILL_SANDBOX_LIMIT_EXCEEDED, "沙箱连接测试响应超过上限");
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
                }
            });
            String value = output.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!running.waitFor(1, java.util.concurrent.TimeUnit.SECONDS))
                throw new java.util.concurrent.TimeoutException();
            if (running.exitValue() != 0)
                throw failure(daemon.authenticationFailed() ? ApiErrorCode.SKILL_SANDBOX_AUTH_FAILED : ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE, "沙箱连接或部署镜像不可用");
            if (expected != null && !value.contains(expected))
                throw failure(ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE, "沙箱主机不满足Linux隔离配额或runtime要求");
        } catch (SkillAccessException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure(ApiErrorCode.SKILL_SANDBOX_TIMEOUT, "沙箱连接测试已中断");
        } catch (java.util.concurrent.TimeoutException failure) {
            throw failure(ApiErrorCode.SKILL_SANDBOX_TIMEOUT, "沙箱连接测试超时");
        } catch (java.io.IOException failure) {
            throw failure(ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE, "沙箱连接测试不可用");
        } catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof SkillAccessException controlled) throw controlled;
            if (error.getCause() instanceof java.io.IOException)
                throw failure(ApiErrorCode.SKILL_SANDBOX_UNAVAILABLE, "沙箱连接测试不可用");
            // 未预期故障交给Controller唯一日志边界；保留发生位置，丢弃外部响应和异常原文。
            var safe = new IllegalStateException("沙箱探测内部执行失败");
            safe.setStackTrace(error.getCause().getStackTrace());
            throw safe;
        } finally {
            if (process != null) process.destroyForcibly();
            workers.shutdownNow();
        }
    }

    /**
     * @param endpoint 已授权端点 @return 固定目标与短期材料，禁止记录Map
     */
    public Map<String, String> connection(SandboxEndpoint endpoint) {
        var ip = targets.validate(endpoint.mode(), endpoint.host(), endpoint.port(), endpoint.username());
        var c = endpoint.mode() == SandboxConnectionMode.LOCAL ? new SandboxCredentials("", "", "", "") : cipher.decrypt(endpoint.tenantId(), endpoint.id(), endpoint.credentialVersion(), endpoint.encryptedCredential());
        validateCredentials(endpoint.mode(), c, endpoint.sshAuthType());
        return parameters(endpoint.mode(), endpoint.host(), ip == null ? "" : ip.getHostAddress(), endpoint.port(), endpoint.username(), c, endpoint.sshAuthType());
    }

    /**
     * 部署默认材料仅从可信文件读取，旧环境兼容不能静默变成其他主机。 @return 默认连接参数
     */
    public Map<String, String> deploymentConnection() {
        var p = properties.getSandbox().getConnection();
        if (p.getMode().isBlank()) {
            String legacy = System.getenv("DOCKER_HOST");
            if (legacy != null && !legacy.isBlank() && !legacy.startsWith("unix:") && !legacy.startsWith("npipe:"))
                throw failure(ApiErrorCode.SKILL_SANDBOX_INVALID, "检测到旧远程DOCKER_HOST，请迁移为显式SSH或TLS沙箱连接配置");
            return Map.of();
        }
        SandboxConnectionMode mode;
        try {
            mode = SandboxConnectionMode.valueOf(p.getMode());
        } catch (IllegalArgumentException error) {
            throw failure(ApiErrorCode.SKILL_SANDBOX_INVALID, "部署沙箱连接方式仅支持LOCAL、SSH或TLS");
        }
        var ip = targets.validate(mode, p.getHost(), p.getPort(), p.getUsername());
        var c = mode == SandboxConnectionMode.LOCAL ? new SandboxCredentials("", "", "", "") :
                new SandboxCredentials(read(p.getPrivateKeyFile()), read(p.getKnownHostsFile()), read(p.getCaCertificateFile()), read(p.getClientCertificateFile()), read(p.getPasswordFile()));
        validateCredentials(mode, c, p.getSshAuthType());
        return parameters(mode, p.getHost(), ip == null ? "" : ip.getHostAddress(), p.getPort(), p.getUsername(), c, p.getSshAuthType());
    }

    private String read(String file) {
        if (file.isBlank()) return "";
        try {
            Path path = Path.of(file);
            if (Files.isSymbolicLink(path) || Files.size(path) > 128 * 1024) throw new java.io.IOException();
            return Files.readString(path);
        } catch (Exception failure) {
            throw failure(ApiErrorCode.SKILL_SANDBOX_CREDENTIAL_UNAVAILABLE, "部署沙箱认证文件不可用");
        }
    }

    private Map<String, String> parameters(SandboxConnectionMode mode, String host, String address, int port, String user, SandboxCredentials c, SandboxSshAuthType auth) {
        // 参数Map只在已授权进程内传递；密码不会进入Docker或SSH子进程环境及审计。
        return Map.ofEntries(Map.entry("mode", mode.name()), Map.entry("host", host), Map.entry("address", address), Map.entry("port", String.valueOf(port)), Map.entry("username", user),
                Map.entry("privateKey", c.privateKey()), Map.entry("knownHosts", c.knownHosts()), Map.entry("caCertificate", c.caCertificate()), Map.entry("clientCertificate", c.clientCertificate()),
                Map.entry("sshAuthType", auth.name()), Map.entry("password", c.password()));
    }

    private void writable() {
        if (!enabled()) throw failure(ApiErrorCode.SKILL_SANDBOX_DISABLED, "部署环境未开放沙箱管理");
        if (!cipher.ready())
            throw failure(ApiErrorCode.SKILL_SANDBOX_CREDENTIAL_UNAVAILABLE, "请先配置部署沙箱凭据主密钥");
    }

    private void validate(String name, String backend, SandboxConnectionMode mode, String host, int port, String user) {
        if (name == null || name.isBlank() || name.length() > 160 || !"docker".equals(backend))
            throw failure(ApiErrorCode.SKILL_SANDBOX_INVALID, "端点名称或后端不合法，当前管理仅支持Docker");
        targets.validate(mode, host, port, user);
    }

    private void validateCredentials(SandboxConnectionMode mode, SandboxCredentials c, SandboxSshAuthType auth) {
        if (auth == null || (mode != SandboxConnectionMode.SSH && auth != SandboxSshAuthType.KEY))
            throw failure(ApiErrorCode.SKILL_SANDBOX_INVALID, "账号密码认证只能用于SSH连接");
        if (mode == SandboxConnectionMode.LOCAL) return;
        if (mode == SandboxConnectionMode.SSH && auth == SandboxSshAuthType.PASSWORD) {
            if (c == null || c.password().isEmpty() || c.knownHosts().isBlank() || !c.privateKey().isBlank() || !c.caCertificate().isBlank() || !c.clientCertificate().isBlank())
                throw failure(ApiErrorCode.SKILL_SANDBOX_INVALID, "SSH账号密码方式需要密码及主机信任材料，不能同时提交私钥或TLS证书");
            return;
        }
        if (c != null && !c.password().isEmpty())
            throw failure(ApiErrorCode.SKILL_SANDBOX_INVALID, "当前认证方式不接受SSH密码");
        if (c == null || c.privateKey().isBlank() || !c.privateKey().contains("PRIVATE KEY") ||
                (mode == SandboxConnectionMode.SSH && c.knownHosts().isBlank()) ||
                (mode == SandboxConnectionMode.TLS && (!c.caCertificate().contains("BEGIN CERTIFICATE") || !c.clientCertificate().contains("BEGIN CERTIFICATE"))))
            throw failure(ApiErrorCode.SKILL_SANDBOX_INVALID, "SSH私钥与主机信任或TLS私钥与证书材料不完整");
    }

    private SandboxEndpoint copy(SandboxEndpoint e, boolean enabled, long revision, long probe, Instant at, String status, boolean deleted) {
        return new SandboxEndpoint(e.id(), e.tenantId(), e.displayName(), e.backend(), e.mode(), e.host(), e.port(), e.username(), enabled,
                e.encryptedCredential(), e.credentialVersion(), revision, probe, at, status, deleted, e.createdAt(), Instant.now(), e.sshAuthType());
    }

    private void event(PrincipalRef p, UUID id, String action) {
        audit.append(p.tenantId(), p.principalId(), "SANDBOX_" + action, "SANDBOX_ENDPOINT", id == null ? "default" : id.toString(), "PROBE_FAILED".equals(action) ? "FAILED" : "SUCCEEDED", "沙箱端点配置操作");
    }

    private static SkillAccessException conflict() {
        return failure(ApiErrorCode.SKILL_SANDBOX_ENDPOINT_CONFLICT, "端点配置已变化，请刷新后重试");
    }

    private static SkillAccessException failure(ApiErrorCode code, String message) {
        return new SkillAccessException(code, message, UUID.randomUUID().toString(), true);
    }
}
