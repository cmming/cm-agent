package com.cmagent.core.domain;

import com.cmagent.core.security.OperationCategory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 远端 Server 派发给已配对设备的一次本机操作记录。
 *
 * <p>请求和结果只保存认证加密后的密文，摘要与错误必须是安全元数据。派发是单向线性化点：
 * 进入 {@link State#DISPATCHED} 后断线、崩溃或超时只能标为未知，不能自动重放同一操作。</p>
 *
 * @param id 服务端生成的操作标识
 * @param tenantId 运行所属可信租户
 * @param ownerId 原认证主体标识
 * @param deviceId 本轮授权设备标识
 * @param agentId 发起调用的 Agent 标识
 * @param conversationId 运行所属会话标识
 * @param runId 运行标识
 * @param toolId 虚拟本机客户端工具标识
 * @param toolCallId Runtime 工具调用标识
 * @param category 服务端从固定工具目录确定的操作类别
 * @param encryptedRequest 已认证加密的请求 JSON
 * @param state 当前派发和结果状态
 * @param encryptedResult 已认证加密的结果 JSON；尚未返回时为空
 * @param safeError 可展示且已脱敏的失败原因
 * @param createdAt 操作登记时间
 * @param expiresAt 结果最晚等待时间，也是客户端不可再取任务的排他上限
 * @param dispatchedAt 客户端实际取得操作的线性化时间
 * @param completedAt 结果或未知状态登记时间
 * @param version 条件更新版本，从一开始
 */
public record LocalClientOperation(
        UUID id,
        UUID tenantId,
        String ownerId,
        UUID deviceId,
        UUID agentId,
        UUID conversationId,
        UUID runId,
        UUID toolId,
        String toolCallId,
        OperationCategory category,
        String encryptedRequest,
        State state,
        String encryptedResult,
        String safeError,
        Instant createdAt,
        Instant expiresAt,
        Instant dispatchedAt,
        Instant completedAt,
        long version
) {
    /** 本机操作的持久状态。 */
    public enum State {
        /** 已登记但尚未交给设备。 */ PENDING,
        /** 已交给设备；后续失去连接不能回退为可重试。 */ DISPATCHED,
        /** 设备返回成功结果。 */ SUCCEEDED,
        /** 设备返回确定的失败结果。 */ FAILED,
        /** 副作用可能发生但结果无法确认。 */ UNKNOWN,
        /** 在派发前被撤销或过期。 */ CANCELLED
    }

    /** 固定身份、密文与线性化时间约束。 */
    public LocalClientOperation {
        Objects.requireNonNull(id, "操作标识不能为空");
        Objects.requireNonNull(tenantId, "租户标识不能为空");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("操作主体不能为空");
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        Objects.requireNonNull(agentId, "智能体标识不能为空");
        Objects.requireNonNull(conversationId, "会话标识不能为空");
        Objects.requireNonNull(runId, "运行标识不能为空");
        Objects.requireNonNull(toolId, "工具标识不能为空");
        if (toolCallId == null || toolCallId.isBlank() || toolCallId.length() > 200) {
            throw new IllegalArgumentException("工具调用标识无效");
        }
        Objects.requireNonNull(category, "操作类别不能为空");
        if (encryptedRequest == null || encryptedRequest.isBlank()) {
            throw new IllegalArgumentException("操作请求密文不能为空");
        }
        Objects.requireNonNull(state, "操作状态不能为空");
        encryptedResult = encryptedResult == null ? "" : encryptedResult;
        safeError = safeError == null ? "" : safeError;
        Objects.requireNonNull(createdAt, "操作创建时间不能为空");
        Objects.requireNonNull(expiresAt, "操作过期时间不能为空");
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("操作过期时间无效");
        if ((state == State.DISPATCHED || state == State.SUCCEEDED || state == State.FAILED
                || state == State.UNKNOWN) && dispatchedAt == null) {
            throw new IllegalArgumentException("已派发或终态操作缺少派发时间");
        }
        if ((state == State.SUCCEEDED || state == State.FAILED || state == State.UNKNOWN
                || state == State.CANCELLED) && completedAt == null) {
            throw new IllegalArgumentException("终态操作缺少完成时间");
        }
        if (version < 1) throw new IllegalArgumentException("操作版本必须大于零");
    }

    /** 不在日志或异常格式化中输出请求、结果或外部错误正文。 */
    @Override
    public String toString() {
        return "LocalClientOperation[id=" + id + ", category=" + category + ", state=" + state + "]";
    }
}
