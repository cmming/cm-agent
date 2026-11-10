package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 已配对设备项目的一次固定工具链命令意图和权威生命周期。
 *
 * <p>完整启动清单只保存在认证加密值中；展示摘要用于绑定精确 HIGH 审批。
 * 命令输出由设备按有界快照回报并在服务端再次脱敏后加密保存。状态推进不自动重放，
 * 已派发但无法取得退出证据时只能保持 {@link State#UNKNOWN}。</p>
 *
 * @param id 服务端命令标识
 * @param tenantId 可信租户
 * @param ownerId 精确原主体
 * @param projectId 固定设备项目
 * @param agentId 固定 Agent
 * @param conversationId 固定会话
 * @param runId 固定 Run
 * @param planDigest 完整客户端执行清单摘要
 * @param displayDigest 精确审批展示摘要
 * @param displayExact 展示是否覆盖全部可审批命令与构建差异
 * @param encryptedPlan 认证加密的完整启动清单
 * @param encryptedOutput 认证加密的脱敏输出快照，无输出时为空
 * @param outputAvailable 服务端是否持有可展示输出快照
 * @param truncated 输出是否达到设备保留上限
 * @param exitCode 已确认根进程退出码，未确认时为空
 * @param state 命令生命周期
 * @param stopRequested 是否已登记一次或多次停止请求
 * @param sequence 输出快照单调序号
 * @param version 条件版本，从 1 开始
 * @param createdAt 提案形成时间
 * @param updatedAt 最近状态或输出更新时间
 */
public record ClientProjectCommand(UUID id, UUID tenantId, String ownerId, UUID projectId,
        UUID agentId, UUID conversationId, UUID runId, String planDigest,
        String displayDigest, boolean displayExact, String encryptedPlan,
        String encryptedOutput, boolean outputAvailable, boolean truncated, Integer exitCode,
        State state, boolean stopRequested, long sequence, long version, Instant createdAt,
        Instant updatedAt) {
    /** 固定命令状态；只有明确退出/回报才能进入成功或失败。 */
    public enum State {
        /** 设备已生成精确清单，尚未取得执行许可。 */
        PROPOSED,
        /** 已取得精确许可并登记唯一启动任务。 */
        QUEUED,
        /** 设备已确认根进程启动，尚未证明退出。 */
        RUNNING,
        /** 已登记停止，仍需等待退出证据。 */
        STOP_REQUESTED,
        /** 根进程以可核验的成功结果退出。 */
        SUCCEEDED,
        /** 明确失败且不再等待原命令。 */
        FAILED,
        /** 已按授权停止并取得退出证据。 */
        CANCELLED,
        /** 已派发但结果或进程静止无法确认，禁止自动重放。 */
        UNKNOWN;

        /** @return 仍需 Run 完成屏障或人工对账的状态 */
        public boolean unsettled() {
            return this == QUEUED || this == RUNNING || this == STOP_REQUESTED
                    || this == UNKNOWN;
        }
    }

    /** 校验可信标识、摘要、输出预算与时间边界。 */
    public ClientProjectCommand {
        Objects.requireNonNull(id, "命令标识不能为空");
        Objects.requireNonNull(tenantId, "租户不能为空");
        Objects.requireNonNull(projectId, "项目不能为空");
        Objects.requireNonNull(agentId, "Agent 不能为空");
        Objects.requireNonNull(conversationId, "会话不能为空");
        Objects.requireNonNull(runId, "Run 不能为空");
        Objects.requireNonNull(encryptedPlan, "加密命令清单不能为空");
        Objects.requireNonNull(encryptedOutput, "加密命令输出不能为空");
        Objects.requireNonNull(state, "命令状态不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
        Objects.requireNonNull(updatedAt, "更新时间不能为空");
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 200
                || planDigest == null || !planDigest.matches("[0-9a-f]{64}")
                || displayDigest == null || !displayDigest.matches("[0-9a-f]{64}")
                || encryptedPlan.isBlank() || sequence < 0 || version < 1
                || updatedAt.isBefore(createdAt)
                || state == State.SUCCEEDED && !Integer.valueOf(0).equals(exitCode)
                || state == State.RUNNING && exitCode != null) {
            throw new IllegalArgumentException("客户端项目命令元数据不合法");
        }
    }

    /**
     * 条件更新状态和输出快照。
     *
     * @param next 目标状态
     * @param stop 新的停止请求标志，只能从 false 变为 true
     * @param exit 根进程退出码；仅成功或失败允许非空
     * @param cut 输出是否截断
     * @param available 是否持有输出快照
     * @param output 加密输出快照，无输出时为空字符串
     * @param nextSequence 单调输出序号
     * @param now 服务端可信时间
     * @return 版本递增后的不可变命令
     */
    public ClientProjectCommand update(State next, boolean stop, Integer exit, boolean cut,
            boolean available, String output, long nextSequence, Instant now) {
        Objects.requireNonNull(next, "命令状态不能为空");
        Objects.requireNonNull(output, "命令输出不能为空");
        if (stopRequested && !stop || nextSequence < sequence || now.isBefore(updatedAt)
                || !allowed(next) || next == State.SUCCEEDED && !Objects.equals(exit, 0)
                || next == State.RUNNING && exit != null
                || (next == State.PROPOSED || next == State.QUEUED)
                    && (exit != null || available || cut)) {
            throw new IllegalStateException("客户端项目命令不能按该路径推进");
        }
        return new ClientProjectCommand(id, tenantId, ownerId, projectId, agentId,
                conversationId, runId, planDigest, displayDigest, displayExact, encryptedPlan,
                output, available, cut, exit, next, stop, nextSequence,
                Math.addExact(version, 1), createdAt, now);
    }

    private boolean allowed(State next) {
        return switch (state) {
            case PROPOSED -> next == State.QUEUED || next == State.CANCELLED;
            // 快命令可能在首次签名回报前已经退出；完整退出回执可合并启动与退出两次观察，零退出码约束仍生效。
            case QUEUED -> next == State.RUNNING || next == State.SUCCEEDED || next == State.STOP_REQUESTED
                    || next == State.FAILED
                    || next == State.CANCELLED || next == State.UNKNOWN;
            case RUNNING -> next == State.RUNNING || next == State.STOP_REQUESTED || next == State.SUCCEEDED
                    || next == State.FAILED || next == State.CANCELLED || next == State.UNKNOWN;
            case STOP_REQUESTED -> next == State.STOP_REQUESTED || next == State.SUCCEEDED
                    || next == State.FAILED || next == State.CANCELLED || next == State.UNKNOWN;
            case UNKNOWN -> next == State.UNKNOWN || next == State.SUCCEEDED
                    || next == State.FAILED || next == State.CANCELLED;
            default -> false;
        };
    }

    /** 不输出密文、命令参数或输出正文。 */
    @Override public String toString() {
        return "ClientProjectCommand[id=" + id + ",state=" + state
                + ",version=" + version + "]";
    }
}
