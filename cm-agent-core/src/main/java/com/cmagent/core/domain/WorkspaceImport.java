package com.cmagent.core.domain;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/**
 * 不可变导入快照及其生命周期；manifest 不可改写，正文只存在私有密文中。
 * @param id 服务端生成的批次标识，亦为 IMPORTED 的 workspaceId
 * @param scope 可信租户、原主体及 Agent
 * @param displayName 用户给出的展示名称，不参与对象寻址或日志
 * @param files 不可变且有界的 manifest
 * @param manifestDigest 包含来源与归属的服务端身份摘要
 * @param state 生命周期状态
 * @param uploaded 已完整验证并持久化的文件标识
 * @param version CAS 版本，从一开始
 * @param createdAt 服务端创建时间
 * @param expiresAt DRAFT 截止或 READY 固定期限，不随读取续期
 * @param reclaimed 物理密文已确认删除，才允许归还配额
 * @param requestId 客户端单次确认的随机关联标识，仅用于结果核对；旧批次为空，不参与对象寻址
 */
public record WorkspaceImport(UUID id, WorkspaceImportScope scope, String displayName, List<WorkspaceImportFile> files,
        String manifestDigest, State state, Set<UUID> uploaded, long version, Instant createdAt, Instant expiresAt,
        boolean reclaimed, UUID requestId) {
    /** 兼容T9已存在的领域构造器，旧批次不伪造请求关联信息。 */
    public WorkspaceImport(UUID id, WorkspaceImportScope scope, String displayName, List<WorkspaceImportFile> files,
            String manifestDigest, State state, Set<UUID> uploaded, long version, Instant createdAt, Instant expiresAt, boolean reclaimed) {
        this(id, scope, displayName, files, manifestDigest, state, uploaded, version, createdAt, expiresAt, reclaimed, null);
    }
    /** 导入生命周期与可观察用途，撤销和过期均不能重新发布。 */
    public enum State {
        /** 已预留配额，文件尚未完整接收，不能绑定会话。 */ DRAFT,
        /** 全部文件完成验证，期限内可被原主体绑定。 */ READY,
        /** 原主体已撤销，立即阻止新运行与恢复。 */ REVOKED,
        /** 服务端期限已到，等待无在途租约后回收。 */ EXPIRED
    }

    public WorkspaceImport {
        Objects.requireNonNull(id); Objects.requireNonNull(scope); Objects.requireNonNull(state);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(expiresAt);
        files = List.copyOf(files); uploaded = Set.copyOf(uploaded);
        if (displayName == null || displayName.isBlank() || displayName.length() > 120 || !StandardCharsets.UTF_8.newEncoder().canEncode(displayName)
                || displayName.contains("/") || displayName.contains("\\") || displayName.contains(":")
                || displayName.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                || files.isEmpty() || files.size() > 8 || files.stream().mapToLong(WorkspaceImportFile::sizeBytes).sum() > 65536
                || files.stream().map(WorkspaceImportFile::id).distinct().count() != files.size()
                || files.stream().map(WorkspaceImportFile::pathIdentity).distinct().count() != files.size()
                || !files.stream().map(WorkspaceImportFile::id).toList().containsAll(uploaded)
                || version < 1 || !expiresAt.isAfter(createdAt)
                || state == State.READY && uploaded.size() != files.size()
                || reclaimed && state != State.REVOKED && state != State.EXPIRED
                || !digest(id, scope, files).equals(manifestDigest))
            throw new IllegalArgumentException("导入清单或状态不合法");
    }

    /**
     * 创建 DRAFT；部署预算由仓储另行校验，硬界不能通过提高配置绕过。
     * @param id 服务端批次标识
     * @param scope 可信归属
     * @param name 展示名称
     * @param files 经验证的有界文件项
     * @param now 服务端时间
     * @param expires DRAFT 截止时间
     * @return 尚不能绑定的批次
     */
    public static WorkspaceImport draft(UUID id, WorkspaceImportScope scope, String name,
            List<WorkspaceImportFile> files, Instant now, Instant expires) {
        return new WorkspaceImport(id, scope, name, files, digest(id, scope, files), State.DRAFT, Set.of(), 1, now, expires, false);
    }

    /** @return 实际预留的明文字节；撤销/过期在物理回收前也占用容量 */
    public long totalBytes() { return files.stream().mapToLong(WorkspaceImportFile::sizeBytes).sum(); }

    /** @return 当前是否可绑定；不能只判断 READY 而忽略期限 */
    public boolean usable(Instant now) { return state == State.READY && !reclaimed && expiresAt.isAfter(now); }

    /** 默认诊断文本不包含展示名称、文件名、manifest 或正文。 */
    @Override public String toString() { return "WorkspaceImport[id=" + id + ", state=" + state + ", version=" + version + "]"; }

    /**
     * 生成无歧义的 manifest 身份；长度前缀防止文件名或主体中的分隔符造成摘要碰撞。
     * @param id 批次标识
     * @param scope 可信归属
     * @param files 固定顺序的文件项
     * @return SHA-256 身份摘要
     */
    public static String digest(UUID id, WorkspaceImportScope scope, List<WorkspaceImportFile> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var parts = new ArrayList<>(List.of("IMPORTED", id.toString(), scope.tenantId().toString(), scope.principalId(), scope.agentId().toString()));
            for (var file : files) parts.addAll(List.of(file.id().toString(), file.relativePath(), Integer.toString(file.sizeBytes()), file.sha256()));
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("缺少必需的摘要算法", impossible); }
    }
}
