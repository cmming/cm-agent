package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 租户端点的不可变持久化版本；凭据只保存认证密文，API 必须使用显式响应投影。
 * @param id 端点标识
 * @param tenantId 可信租户标识
 * @param displayName 中文显示名称
 * @param backend 已注册后端标识
 * @param mode 连接协议
 * @param host 经过部署策略校验的主机，本地为空
 * @param port 远程端口，本地为零
 * @param username SSH 登录用户，其他方式为空
 * @param enabled 是否允许新执行
 * @param encryptedCredential 包含私钥、SSH密码、证书及信任材料的认证密文
 * @param credentialVersion 凭据版本，作为加密附加认证数据
 * @param revision 乐观并发配置版本
 * @param probeRevision 最后成功探测的配置版本，零表示未通过
 * @param probedAt 最后探测时间，可为空
 * @param probeStatus 安全探测结果，不包含远端原文
 * @param deleted 逻辑删除标记，保留已有执行清理所需材料
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param sshAuthType SSH认证方式；旧端点与非SSH协议默认KEY
 */
public record SandboxEndpoint(UUID id, UUID tenantId, String displayName, String backend,
        SandboxConnectionMode mode, String host, int port, String username, boolean enabled,
        String encryptedCredential, long credentialVersion, long revision, long probeRevision,
        Instant probedAt, String probeStatus, boolean deleted, Instant createdAt, Instant updatedAt, SandboxSshAuthType sshAuthType) {
    public SandboxEndpoint {
        Objects.requireNonNull(id); Objects.requireNonNull(tenantId); Objects.requireNonNull(mode);
        Objects.requireNonNull(displayName); Objects.requireNonNull(backend);
        Objects.requireNonNull(host); Objects.requireNonNull(username);
        Objects.requireNonNull(encryptedCredential); Objects.requireNonNull(probeStatus);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        sshAuthType = sshAuthType == null ? SandboxSshAuthType.KEY : sshAuthType;
        if (mode != SandboxConnectionMode.SSH && sshAuthType != SandboxSshAuthType.KEY)
            throw new IllegalArgumentException("账号密码认证只能用于SSH连接");
        if (revision < 1 || credentialVersion < 0 || port < 0 || port > 65535)
            throw new IllegalArgumentException("沙箱端点版本或端口不合法");
    }
    /** 保留R1构造调用的私钥默认语义，不把旧端点自动转换为密码认证。 */
    public SandboxEndpoint(UUID id, UUID tenantId, String displayName, String backend, SandboxConnectionMode mode,
            String host, int port, String username, boolean enabled, String encryptedCredential, long credentialVersion,
            long revision, long probeRevision, Instant probedAt, String probeStatus, boolean deleted, Instant createdAt, Instant updatedAt) {
        this(id,tenantId,displayName,backend,mode,host,port,username,enabled,encryptedCredential,credentialVersion,
            revision,probeRevision,probedAt,probeStatus,deleted,createdAt,updatedAt,SandboxSshAuthType.KEY);
    }
    /** 防止诊断时隐式输出内部地址及密文。 */
    @Override public String toString() { return "SandboxEndpoint[id=" + id + ", revision=" + revision + "]"; }
}
