package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 记录一次 Agent 运行的主体、类型、状态、输入输出和起止时间。
 *
 * @param id 运行记录唯一标识
 * @param tenantId 运行归属租户
 * @param agentId 被运行的 Agent 标识
 * @param principalId 发起运行的租户内主体标识
 * @param kind 正式运行或技能试运行
 * @param status 当前运行状态
 * @param input 调用方输入，保存前已脱敏
 * @param output 模型或工具输出，保存前已脱敏
 * @param errorMessage 已脱敏的错误说明
 * @param startedAt 运行开始时间
 * @param finishedAt 运行完成时间；非终态必须为空
 */
public record RunRecord(
        UUID id,
        UUID tenantId,
        UUID agentId,
        String principalId,
        RunKind kind,
        RunStatus status,
        String input,
        String output,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
    /** 校验租户上下文、运行类型和状态时间不变量。 */
    public RunRecord {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        if (principalId == null || principalId.isBlank()) {
            throw new IllegalArgumentException("principalId 不能为空");
        }
        Objects.requireNonNull(kind, "kind 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        input = input == null ? "" : input;
        output = output == null ? "" : output;
        errorMessage = errorMessage == null ? "" : errorMessage;
        Objects.requireNonNull(startedAt, "startedAt 不能为空");
        if (status == RunStatus.RUNNING && finishedAt != null) {
            throw new IllegalArgumentException("RUNNING 状态不能有 finishedAt");
        }
        if (status == RunStatus.WAITING_APPROVAL && finishedAt != null) {
            throw new IllegalArgumentException("WAITING_APPROVAL 状态不能有 finishedAt");
        }
        if (!status.isActive() && finishedAt == null) {
            throw new IllegalArgumentException("终态必须有 finishedAt");
        }
        if (finishedAt != null && finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt 不能早于 startedAt");
        }
    }

    /** 兼容旧持久化映射和调用方，缺省为正式运行。 */
    public RunRecord(
            UUID id, UUID tenantId, UUID agentId, String principalId, RunStatus status,
            String input, String output, String errorMessage, Instant startedAt, Instant finishedAt
    ) {
        this(id, tenantId, agentId, principalId, RunKind.NORMAL, status,
                input, output, errorMessage, startedAt, finishedAt);
    }

    /** 创建正式运行中的初始记录。 */
    public static RunRecord create(
            UUID id, UUID tenantId, UUID agentId, String principalId, String input, Instant startedAt
    ) {
        return create(id, tenantId, agentId, principalId, RunKind.NORMAL, input, startedAt);
    }

    /**
     * 创建指定类型的运行中记录。
     *
     * @param id 运行标识
     * @param tenantId 可信租户标识
     * @param agentId 已校验归属的 Agent 标识
     * @param principalId 发起主体标识
     * @param kind 正式或试运行类型
     * @param input 已脱敏输入
     * @param startedAt 开始时间
     * @return 新建的运行中记录
     */
    public static RunRecord create(
            UUID id, UUID tenantId, UUID agentId, String principalId,
            RunKind kind, String input, Instant startedAt
    ) {
        return new RunRecord(id, tenantId, agentId, principalId, kind,
                RunStatus.RUNNING, input, "", "", startedAt, null);
    }

    /** 将当前活动记录转换为成功或失败终态并保留运行类型。 */
    public RunRecord complete(RunStatus status, String output, String errorMessage, Instant finishedAt) {
        if (status == RunStatus.RUNNING) {
            throw new IllegalArgumentException("finalStatus 不能为 RUNNING");
        }
        if (status == RunStatus.WAITING_APPROVAL) {
            throw new IllegalArgumentException("finalStatus 不能为 WAITING_APPROVAL");
        }
        if (!this.status.isActive()) {
            throw new IllegalStateException("只能完成 RUNNING 状态的运行");
        }
        return new RunRecord(
                id, tenantId, agentId, principalId, kind, status, input, output, errorMessage,
                startedAt, Objects.requireNonNull(finishedAt, "finishedAt 不能为空"));
    }

    /**
     * 将运行中记录转换为等待审批状态。
     *
     * <p>暂停不是完成，因此不写入完成时间；正式和试运行审批均保留原运行类型。</p>
     */
    public RunRecord waitForApproval() {
        if (status != RunStatus.RUNNING) {
            throw new IllegalStateException("只能暂停 RUNNING 状态的运行");
        }
        return new RunRecord(id, tenantId, agentId, principalId, kind,
                RunStatus.WAITING_APPROVAL, input, "", "", startedAt, null);
    }
}
