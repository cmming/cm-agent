package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 已配对设备项目的一次受保护文件变更意图。
 *
 * <p>计划正文只保存在认证加密值中；计划摘要用于绑定的 HIGH 工具审批，
 * 展示摘要用于确认脱敏差异没有掩盖真实变化。操作本身不授予新的 Run 权限。</p>
 *
 * @param id 服务端操作编号，也作为客户端计划任务编号
 * @param tenantId 可信租户
 * @param ownerId 精确原主体
 * @param projectId 固定设备项目
 * @param agentId 固定 Agent
 * @param conversationId 固定会话
 * @param runId 固定 Run
 * @param undoOf 条件撤销时的原成功操作；普通变更为空
 * @param planDigest 原始计划摘要，绑定 HIGH 审批和条件执行
 * @param displayDigest 脱敏预览摘要，只用于确认用户看到的内容
 * @param displayExact 脱敏是否完整保留所有实际差异；false 时禁止批准
 * @param encryptedPlan 认证加密的计划和条件版本
 * @param state 操作生命周期
 * @param version 条件版本，从 1 开始
 * @param createdAt 计划形成时间
 * @param updatedAt 最近状态时间
 */
public record ClientProjectOperation(UUID id, UUID tenantId, String ownerId, UUID projectId,
        UUID agentId, UUID conversationId, UUID runId, UUID undoOf, String planDigest,
        String displayDigest, boolean displayExact, String encryptedPlan, State state,
        long version, Instant createdAt, Instant updatedAt) {
    /** 只有文件变更；命令仍由后续 T6/T17 独立实现。 */
    public enum Kind {
        /** 已预览且尚未应用的文件变更。 */
        FILE_CHANGE
    }

    /** 服务端条件状态；审批证据由既有 ToolApprovalRepository 保存。 */
    public enum State {
        /** 计划已由设备核对，尚未取得执行许可。 */
        PROPOSED,
        /** 已取得一次派发许可，设备副作用可能发生。 */
        APPLYING,
        /** 设备回报且服务端核验成功。 */
        SUCCEEDED,
        /** 在明确副作用发生前失败。 */
        FAILED,
        /** 已派发但结果不能确认，禁止自动重放。 */
        UNKNOWN,
        /** 尚未派发即取消。 */
        CANCELLED
    }

    public ClientProjectOperation {
        Objects.requireNonNull(id, "操作编号不能为空");
        Objects.requireNonNull(tenantId, "租户不能为空");
        Objects.requireNonNull(projectId, "项目不能为空");
        Objects.requireNonNull(agentId, "Agent 不能为空");
        Objects.requireNonNull(conversationId, "会话不能为空");
        Objects.requireNonNull(runId, "Run 不能为空");
        Objects.requireNonNull(state, "操作状态不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
        Objects.requireNonNull(updatedAt, "更新时间不能为空");
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 200
                || planDigest == null || !planDigest.matches("[0-9a-f]{64}")
                || displayDigest == null || !displayDigest.matches("[0-9a-f]{64}")
                || encryptedPlan == null || encryptedPlan.isBlank()
                || version < 1 || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("客户端项目操作元数据不合法");
        }
    }

    /**
     * 条件推进状态，不执行客户端文件操作。
     *
     * @param next 目标状态
     * @param now 服务端可信时间
     * @return 保留不可变计划的新版本
     */
    public ClientProjectOperation advance(State next, Instant now) {
        boolean allowed = switch (state) {
            case PROPOSED -> next == State.APPLYING || next == State.CANCELLED;
            case APPLYING -> next == State.SUCCEEDED || next == State.FAILED
                    || next == State.UNKNOWN || next == State.CANCELLED;
            case UNKNOWN -> next == State.SUCCEEDED || next == State.FAILED
                    || next == State.CANCELLED;
            default -> false;
        };
        if (!allowed || now.isBefore(updatedAt)) {
            throw new IllegalStateException("客户端项目操作状态不能按该路径推进");
        }
        return new ClientProjectOperation(id, tenantId, ownerId, projectId, agentId,
                conversationId, runId, undoOf, planDigest, displayDigest, displayExact,
                encryptedPlan, next, Math.addExact(version, 1), createdAt, now);
    }

    @Override
    public String toString() {
        return "ClientProjectOperation[id=" + id + ",state=" + state
                + ",version=" + version + "]";
    }
}
