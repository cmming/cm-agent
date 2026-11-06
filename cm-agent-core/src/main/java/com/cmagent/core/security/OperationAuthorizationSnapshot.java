package com.cmagent.core.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 由服务端为一轮运行固定的操作授权范围。
 *
 * <p>本对象只保存授权事实的不可变内容，不证明授权仍然有效。使用时必须从可信仓储读取并同时
 * 复核用户、设备、工具授权、撤销状态和运行状态，不能把客户端提交的同结构数据视为授权。</p>
 *
 * @param authorizationId 服务端生成的授权事实标识
 * @param tenantId 认证主体所属租户
 * @param principalId 确认本轮授权的原主体标识
 * @param agentId 运行所属智能体标识
 * @param conversationId 提供在线审批入口的会话标识
 * @param runId 本次授权唯一适用的运行标识
 * @param deviceId 本次授权唯一适用的已配对设备标识
 * @param mode 用户选择且服务端允许的操作确认方式
 * @param categories 服务端允许在本轮使用的操作类别
 * @param policyVersion 固定的可信策略版本，从一开始
 * @param grantedAt 服务端记录的授权时间
 * @param expiresAt 服务端确定的过期时间，不得早于或等于授权时间
 */
public record OperationAuthorizationSnapshot(
        UUID authorizationId,
        UUID tenantId,
        String principalId,
        UUID agentId,
        UUID conversationId,
        UUID runId,
        UUID deviceId,
        OperationAuthorizationMode mode,
        Set<OperationCategory> categories,
        long policyVersion,
        Instant grantedAt,
        Instant expiresAt
) {
    /** 校验授权绑定并冻结能力集合，防止创建后被外部引用扩大范围。 */
    public OperationAuthorizationSnapshot {
        Objects.requireNonNull(authorizationId, "授权标识不能为空");
        Objects.requireNonNull(tenantId, "租户标识不能为空");
        if (principalId == null || principalId.isBlank()) {
            throw new IllegalArgumentException("主体标识不能为空");
        }
        Objects.requireNonNull(agentId, "智能体标识不能为空");
        Objects.requireNonNull(conversationId, "会话标识不能为空");
        Objects.requireNonNull(runId, "运行标识不能为空");
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        Objects.requireNonNull(mode, "授权方式不能为空");
        categories = Set.copyOf(Objects.requireNonNull(categories, "操作类别不能为空"));
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("操作类别不能为空");
        }
        if (policyVersion < 1) {
            throw new IllegalArgumentException("策略版本必须大于零");
        }
        Objects.requireNonNull(grantedAt, "授权时间不能为空");
        Objects.requireNonNull(expiresAt, "过期时间不能为空");
        if (!expiresAt.isAfter(grantedAt)) {
            throw new IllegalArgumentException("过期时间必须晚于授权时间");
        }
    }

    /**
     * 判断服务端时钟是否位于授权的时间窗口。
     *
     * <p>过期时间为排他上限；时间窗口有效不代表授权未撤销或运行仍可派发。</p>
     *
     * @param now 由服务端可信时钟提供的当前时间
     * @return 授权已开始且尚未到期时为 {@code true}
     */
    public boolean isWithinTimeWindow(Instant now) {
        Objects.requireNonNull(now, "当前时间不能为空");
        return !now.isBefore(grantedAt) && now.isBefore(expiresAt);
    }
}
