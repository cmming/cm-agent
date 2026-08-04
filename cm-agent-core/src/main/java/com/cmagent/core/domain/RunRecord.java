package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 记录一次 Agent 运行的主体、状态、输入输出和起止时间。
 */
public record RunRecord(
        UUID id,
        UUID tenantId,
        UUID agentId,
        String principalId,
        RunStatus status,
        String input,
        String output,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
    /**
     * 校验运行记录的租户上下文、状态和起止时间不变量。
      *
      * @param id 目标资源标识
      * @param tenantId 当前租户标识
      * @param agentId 目标 Agent 标识
      * @param principalId 租户内主体标识
      * @param status 目标运行状态
      * @param input 调用方输入
      * @param output 模型或工具输出
      * @param errorMessage 已控制敏感信息的错误说明
      * @param startedAt 流程开始时间
      * @param finishedAt 流程完成时间
     */
    public RunRecord {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        if (principalId == null || principalId.isBlank()) {
            throw new IllegalArgumentException("principalId 不能为空");
        }
        input = input == null ? "" : input;
        output = output == null ? "" : output;
        errorMessage = errorMessage == null ? "" : errorMessage;
        Objects.requireNonNull(startedAt, "startedAt 不能为空");
        if (status == RunStatus.RUNNING && finishedAt != null) {
            throw new IllegalArgumentException("RUNNING 状态不能有 finishedAt");
        }
        if (status != RunStatus.RUNNING && finishedAt == null) {
            throw new IllegalArgumentException("终态必须有 finishedAt");
        }
        if (finishedAt != null && finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt 不能早于 startedAt");
        }
    }

    /**
     * 创建处于运行中状态的初始运行记录。
      *
      * @param id 目标资源标识
      * @param tenantId 当前租户标识
      * @param agentId 目标 Agent 标识
      * @param principalId 租户内主体标识
      * @param input 调用方输入
     * @param startedAt 流程开始时间
     * @return 新建的运行记录
     */
    public static RunRecord create(
            UUID id,
            UUID tenantId,
            UUID agentId,
            String principalId,
            String input,
            Instant startedAt
    ) {
        return new RunRecord(id, tenantId, agentId, principalId, RunStatus.RUNNING, input, "", "", startedAt, null);
    }

    /**
     * 将当前运行中记录转换为成功或失败的终态记录。
      *
      * @param status 目标运行状态
      * @param output 模型或工具输出
      * @param errorMessage 已控制敏感信息的错误说明
     * @param finishedAt 流程完成时间
     * @return 保留原始上下文并写入终态信息的新记录
     */
    public RunRecord complete(RunStatus status, String output, String errorMessage, Instant finishedAt) {
        if (status == RunStatus.RUNNING) {
            throw new IllegalArgumentException("finalStatus 不能为 RUNNING");
        }
        if (this.status != RunStatus.RUNNING) {
            throw new IllegalStateException("只能完成 RUNNING 状态的运行");
        }
        return new RunRecord(
                id,
                tenantId,
                agentId,
                principalId,
                status,
                input,
                output,
                errorMessage,
                startedAt,
                Objects.requireNonNull(finishedAt, "finishedAt 不能为空")
        );
    }
}
