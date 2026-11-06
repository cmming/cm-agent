package com.cmagent.core.repository;

import com.cmagent.core.domain.LocalClientOperation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 本机客户端任务队列、单向派发和结果收口合同。
 *
 * <p>请求和结果密文由服务端可信加密边界生成。仓储必须保证同一设备最多领取一条任务、
 * 同一操作最多派发一次，并允许派发后安全地变为未知而不能重新排队。</p>
 */
public interface LocalClientOperationRepository {
    /**
     * 为仍有效的设备与运行授权登记待派发操作。
     *
     * @param operation 操作身份及已加密请求
     * @return 设备、授权或运行仍有效并成功登记时为 {@code true}
     */
    boolean insertOperation(LocalClientOperation operation);

    /**
     * 原子领取该设备最早一条有效待派发操作并标记为 {@link LocalClientOperation.State#DISPATCHED}。
     *
     * @param tenantId 设备可信租户
     * @param ownerId 设备原主体
     * @param deviceId 已完成本次挑战证明的设备
     * @param now 服务端当前时间
     * @return 本次唯一领取到的操作
     */
    Optional<LocalClientOperation> claimNextOperation(
            UUID tenantId, String ownerId, UUID deviceId, Instant now);

    /**
     * 按设备归属读取操作记录。
     *
     * @param tenantId 可信租户
     * @param ownerId 原主体
     * @param deviceId 设备标识
     * @param operationId 操作标识
     * @return 匹配操作
     */
    Optional<LocalClientOperation> findOperation(
            UUID tenantId, String ownerId, UUID deviceId, UUID operationId);

    /**
     * 条件写入客户端返回的终态结果。
     *
     * @param operationId 操作标识
     * @param tenantId 可信租户
     * @param ownerId 原主体
     * @param deviceId 设备标识
     * @param state 只允许成功、失败或未知终态
     * @param encryptedResult 认证加密的结果 JSON；失败时可为空
     * @param safeError 已脱敏错误原因
     * @param expectedVersion 客户端领取时看到的版本
     * @param now 服务端当前时间
     * @return 本次首次登记终态时为 {@code true}
     */
    boolean completeOperation(
            UUID operationId, UUID tenantId, String ownerId, UUID deviceId,
            LocalClientOperation.State state, String encryptedResult, String safeError,
            long expectedVersion, Instant now);

    /**
     * 将已派发但失去确定结果的操作收口为未知。
     *
     * @param operationId 操作标识
     * @param tenantId 可信租户
     * @param ownerId 原主体
     * @param deviceId 设备标识
     * @param safeError 已脱敏原因
     * @param expectedVersion 当前读取版本
     * @param now 服务端当前时间
     * @return 状态从派发变为未知时为 {@code true}
     */
    boolean markUnknown(
            UUID operationId, UUID tenantId, String ownerId, UUID deviceId,
            String safeError, long expectedVersion, Instant now);

    /**
     * 在尚未派发时取消操作，用于严格审计失败或运行撤销。
     *
     * @param operationId 操作标识
     * @param tenantId 可信租户
     * @param ownerId 原主体
     * @param deviceId 设备标识
     * @param expectedVersion 当前版本
     * @param now 取消时间
     * @return 状态从待派发变为取消时为 {@code true}
     */
    boolean cancelPending(
            UUID operationId, UUID tenantId, String ownerId, UUID deviceId,
            long expectedVersion, Instant now);
}
