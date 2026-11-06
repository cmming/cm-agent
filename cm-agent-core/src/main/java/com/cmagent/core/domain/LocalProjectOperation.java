package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 文件或进程副作用的不可变意图及条件状态，文件系统/进程与数据库不构成原子事务。
 * @param id 操作幂等标识，不允许重复创建覆盖
 * @param tenantId 可信租户
 * @param ownerId 原操作主体
 * @param projectId 固定项目
 * @param agentId 固定Agent
 * @param conversationId 固定会话
 * @param runId 固定Run
 * @param kind 文件变更或命令
 * @param digest 精确变更/启动清单的SHA256
 * @param encryptedPayload 受保护的不可变操作清单，不存明文补丁或命令环境
 * @param state 当前条件状态，UNKNOWN禁止自动重放
 * @param version 条件版本，从1开始
 * @param createdAt 创建时间
 * @param updatedAt 状态更新时间
 */
public record LocalProjectOperation(UUID id, UUID tenantId, String ownerId, UUID projectId, UUID agentId,
        UUID conversationId, UUID runId, Kind kind, String digest, String encryptedPayload,
        State state, long version, Instant createdAt, Instant updatedAt) {
    /** 副作用类别，不能把命令的外部影响解释为文件撤销。 */
    public enum Kind {
        /** 已预览的文件变更批次。 */ FILE_CHANGE,
        /** 精确程序/参数/环境/预算的本机执行。 */ COMMAND
    }
    /** 开始执行前先持久化意图，进程重建不能把不确定状态重新当成未执行。 */
    public enum State {
        /** 已形成不可变提案，未产生文件或进程副作用。 */ PROPOSED,
        /** 等待用户对精确清单作决定。 */ WAITING_APPROVAL,
        /** 审批及执行前置条件已记录，尚不能假定没有崩溃窗口。 */ PREPARED,
        /** 已获一次派发权，副作用可能已经发生。 */ RUNNING,
        /** 结果已核实成功并严格持久化。 */ SUCCEEDED,
        /** 已核实的失败，可能伴随已记录的部分文件变更。 */ FAILED,
        /** 原意图已终结；自动停止须确认Job退出，UNKNOWN亦可由原主体核对后显式终结，均不表示撤销副作用。 */ CANCELLED,
        /** 结果不确定，须人工或受控核对，不允许自动重放。 */ UNKNOWN
    }
    public LocalProjectOperation {
        Objects.requireNonNull(id); Objects.requireNonNull(tenantId); Objects.requireNonNull(projectId);
        Objects.requireNonNull(agentId); Objects.requireNonNull(conversationId); Objects.requireNonNull(runId);
        Objects.requireNonNull(kind); Objects.requireNonNull(state); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        LocalProjectCiphertext.require(encryptedPayload, 4194304);
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 200
                || digest == null || !digest.matches("[0-9a-f]{64}")
                || encryptedPayload == null || encryptedPayload.isBlank() || version < 1 || updatedAt.isBefore(createdAt))
            throw new IllegalArgumentException("项目操作元数据不合法");
    }

    /**
     * 条件推进可观察状态，不执行文件或进程操作；终态和UNKNOWN禁止再回到可派发状态。
     * @param next 目标状态
     * @param now 可信状态时间，不能倒退
     * @return 保留不可变清单的新版本
     */
    public LocalProjectOperation advance(State next, Instant now) {
        boolean allowed = switch (state) {
            case PROPOSED -> next == State.WAITING_APPROVAL || next == State.CANCELLED;
            case WAITING_APPROVAL -> next == State.PREPARED || next == State.CANCELLED || next == State.FAILED;
            case PREPARED -> next == State.RUNNING || next == State.CANCELLED || next == State.UNKNOWN || next == State.FAILED;
            case RUNNING -> next == State.SUCCEEDED || next == State.FAILED || next == State.CANCELLED || next == State.UNKNOWN;
            case UNKNOWN -> next == State.SUCCEEDED || next == State.FAILED || next == State.CANCELLED;
            default -> false;
        };
        if (!allowed || now.isBefore(updatedAt)) throw new IllegalStateException("项目操作状态不能按该路径推进");
        return new LocalProjectOperation(id, tenantId, ownerId, projectId, agentId, conversationId, runId,
                kind, digest, encryptedPayload, next, Math.addExact(version, 1), createdAt, now);
    }

    /** 操作清单密文也不进入默认字符串，避免意外诊断泄漏。 */
    @Override public String toString() { return "LocalProjectOperation[id=" + id + ",state=" + state + ",version=" + version + "]"; }
}

