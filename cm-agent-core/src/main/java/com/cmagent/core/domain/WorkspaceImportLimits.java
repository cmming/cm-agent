package com.cmagent.core.domain;

import java.time.Duration;

/**
 * 服务端导入预算，DRAFT、撤销和过期数据在物理删除前均计入配额。
 * @param maxFiles 单批次文件数
 * @param maxFileBytes 单文件字节
 * @param maxTotalBytes 单批次总字节
 * @param ownerImports 主体跨 Agent 的批次数上限
 * @param tenantImports 租户批次数上限
 * @param ownerBytes 主体跨 Agent 的总字节上限
 * @param tenantBytes 租户总字节上限
 * @param draftTtl DRAFT 固定期限，最长一小时
 * @param readyTtl READY 固定期限，最长三十天
 */
public record WorkspaceImportLimits(int maxFiles, int maxFileBytes, int maxTotalBytes, int ownerImports,
        int tenantImports, long ownerBytes, long tenantBytes, Duration draftTtl, Duration readyTtl) {
    public WorkspaceImportLimits {
        if (maxFiles < 1 || maxFiles > 8 || maxFileBytes < 1 || maxFileBytes > 32768 || maxTotalBytes < maxFileBytes
                || maxTotalBytes > 65536 || ownerImports < 1 || ownerImports > 8 || tenantImports < ownerImports || tenantImports > 64
                || ownerBytes < maxTotalBytes || ownerBytes > 524288 || tenantBytes < ownerBytes || tenantBytes > 4194304
                || draftTtl == null || draftTtl.compareTo(Duration.ofMinutes(1)) < 0 || draftTtl.compareTo(Duration.ofHours(1)) > 0
                || readyTtl == null || readyTtl.compareTo(Duration.ofMinutes(1)) < 0 || readyTtl.compareTo(Duration.ofDays(30)) > 0)
            throw new IllegalArgumentException("导入预算或期限不合法");
    }

    /** @return 4文件、16KiB/32KiB 与小配额的默认只读策略 */
    public static WorkspaceImportLimits defaults() {
        return new WorkspaceImportLimits(4, 16384, 32768, 8, 64, 262144, 2097152, Duration.ofHours(1), Duration.ofDays(30));
    }
}
