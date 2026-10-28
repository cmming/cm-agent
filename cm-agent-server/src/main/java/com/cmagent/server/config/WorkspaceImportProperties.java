package com.cmagent.server.config;

import com.cmagent.core.domain.WorkspaceImportLimits;
import java.time.Duration;

/** 导入输入的部署策略；密钥没有可用默认值，也不生成包含字段值的 toString。 */
public final class WorkspaceImportProperties {
    /** 独立入口默认关闭，顶层工作区关闭时不能开启。 */
    private boolean enabled;
    /** 独立私有本地持久卷；与 catalog/Skill 产物分开。 */
    private String rootDirectory = "";
    /** Base64 AES-256 独立密钥，缺失时拒绝初始化存储。 */
    private String encryptionKey = "";
    /** 单主体跨 Agent 仍保留的批次数。 */
    private int maxImportsPerOwner = 8;
    /** 单租户仍保留的批次数。 */
    private int maxImportsPerTenant = 64;
    /** 主体未物理清理的明文字节预留。 */
    private long maxBytesPerOwner = 262144;
    /** 租户未物理清理的明文字节预留。 */
    private long maxBytesPerTenant = 2097152;
    /** 草稿最长一小时，不随上传续期。 */
    private Duration draftTtl = Duration.ofHours(1);
    /** 完成后固定期限，最长三十天。 */
    private Duration readyTtl = Duration.ofDays(30);
    /** 故障补偿和到期清理周期。 */
    private Duration cleanupInterval = Duration.ofMinutes(5);

    /** @return 独立上传开关 */
    public boolean isEnabled() { return enabled; }
    /** @param value 部署者显式开启上传 */
    public void setEnabled(boolean value) { enabled = value; }
    /** @return 私有持久卷 */
    public String getRootDirectory() { return rootDirectory; }
    /** @param value 私有持久卷 */
    public void setRootDirectory(String value) { rootDirectory = value; }
    /** @return 仅存储初始化可读取的密钥，不得用于响应或日志 */
    public String getEncryptionKey() { return encryptionKey; }
    /** @param value 独立部署密钥 */
    public void setEncryptionKey(String value) { encryptionKey = value; }
    /** @return 主体批次数配额 */
    public int getMaxImportsPerOwner() { return maxImportsPerOwner; }
    /** @param value 主体批次数配额 */
    public void setMaxImportsPerOwner(int value) { maxImportsPerOwner = value; }
    /** @return 租户批次数配额 */
    public int getMaxImportsPerTenant() { return maxImportsPerTenant; }
    /** @param value 租户批次数配额 */
    public void setMaxImportsPerTenant(int value) { maxImportsPerTenant = value; }
    /** @return 主体字节配额 */
    public long getMaxBytesPerOwner() { return maxBytesPerOwner; }
    /** @param value 主体字节配额 */
    public void setMaxBytesPerOwner(long value) { maxBytesPerOwner = value; }
    /** @return 租户字节配额 */
    public long getMaxBytesPerTenant() { return maxBytesPerTenant; }
    /** @param value 租户字节配额 */
    public void setMaxBytesPerTenant(long value) { maxBytesPerTenant = value; }
    /** @return 草稿期限 */
    public Duration getDraftTtl() { return draftTtl; }
    /** @param value 草稿期限 */
    public void setDraftTtl(Duration value) { draftTtl = value; }
    /** @return 固定就绪期限 */
    public Duration getReadyTtl() { return readyTtl; }
    /** @param value 固定就绪期限 */
    public void setReadyTtl(Duration value) { readyTtl = value; }
    /** @return 清理周期 */
    public Duration getCleanupInterval() { return cleanupInterval; }
    /** @param value 清理周期 */
    public void setCleanupInterval(Duration value) { cleanupInterval = value; }

    /** @return 继承原工作区文件预算，不能独立放大单次资料上限 */
    public WorkspaceImportLimits limits(WorkspaceContextProperties parent) {
        return new WorkspaceImportLimits(parent.getMaxFiles(), parent.getMaxFileBytes(), parent.getMaxTotalBytes(),
                maxImportsPerOwner, maxImportsPerTenant, maxBytesPerOwner, maxBytesPerTenant, draftTtl, readyTtl);
    }

    /** 配置错误拒绝启动，不在异常中携带根目录或密钥值。 */
    public void validate(WorkspaceContextProperties parent) {
        try {
            limits(parent);
            if (cleanupInterval == null || cleanupInterval.compareTo(Duration.ofSeconds(10)) < 0 || cleanupInterval.compareTo(Duration.ofHours(1)) > 0
                    || enabled && (!parent.isEnabled() || rootDirectory == null || rootDirectory.isBlank())) throw new IllegalArgumentException();
            // 已配置卷时即使关闭新上传也保留读取/删除所需密钥，不能关闭开关就丢弃旧数据生命周期。
            if (rootDirectory != null && !rootDirectory.isBlank() && java.util.Base64.getDecoder().decode(encryptionKey).length != 32)
                throw new IllegalArgumentException();
            if (rootDirectory != null && !rootDirectory.isBlank() && parent.getRootDirectory() != null && !parent.getRootDirectory().isBlank()) {
                var imported = java.nio.file.Path.of(rootDirectory).toAbsolutePath().normalize();
                var registered = java.nio.file.Path.of(parent.getRootDirectory()).toAbsolutePath().normalize();
                if (imported.startsWith(registered) || registered.startsWith(imported)) throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) { throw new IllegalStateException("导入策略、私有卷或独立密钥未正确配置"); }
    }
}
