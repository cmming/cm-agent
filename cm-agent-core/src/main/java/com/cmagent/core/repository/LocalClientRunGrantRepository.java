package com.cmagent.core.repository;

import com.cmagent.core.security.OperationAuthorizationSnapshot;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 单轮本机设备授权的可信持久化合同。
 *
 * <p>认证主体、会话和运行归属由服务端校验；客户端或模型提交的同结构对象不能直接作为
 * 授权依据。服务端创建或撤销授权时必须在共享短事务中写入严格审计。</p>
 */
public interface LocalClientRunGrantRepository {
    /**
     * 保存一轮已由原主体确认的授权；同一租户和运行最多保存一次。
     *
     * @param grant 经服务端认证、权限及用户选择校验的不可变授权
     * @return 保存成功时为 {@code true}；设备无效或本轮已有授权时为 {@code false}
     */
    boolean insertGrant(OperationAuthorizationSnapshot grant);

    /**
     * 查询原主体在指定运行和设备上的未撤销有效授权。
     *
     * <p>该查询是只读快照，不能单独证明后续派发安全；派发登记必须与撤销竞争并复核状态。</p>
     *
     * @param tenantId 可信租户
     * @param ownerId 可信原主体
     * @param runId 运行标识
     * @param deviceId 已配对设备标识
     * @param now 服务端当前时间
     * @return 身份、时间、运行及设备均有效时的授权
     */
    Optional<OperationAuthorizationSnapshot> findActiveGrant(
            UUID tenantId, String ownerId, UUID runId, UUID deviceId, Instant now);

    /**
     * 查询原主体在一轮运行中的唯一有效设备授权。
     *
     * <p>运行开始时相同租户与 Run 只能成功保存一次授权。该查询用于 Runtime 恢复和工具网关
     * 复核；返回快照后仍须在真实派发登记事务内重新校验状态。</p>
     *
     * @param tenantId 可信租户
     * @param ownerId 原主体标识
     * @param runId 运行标识
     * @param now 服务端当前时间
     * @return 未撤销且仍有效的本轮授权
     */
    Optional<OperationAuthorizationSnapshot> findActiveGrantByRun(
            UUID tenantId, String ownerId, UUID runId, Instant now);

    /**
     * 撤销原主体的一轮授权；已派发的外部操作不因撤销自动回滚。
     *
     * @param tenantId 可信租户
     * @param ownerId 可信原主体
     * @param grantId 授权标识
     * @param now 服务端当前时间
     * @return 本次首次撤销成功时为 {@code true}
     */
    boolean revokeGrant(UUID tenantId, String ownerId, UUID grantId, Instant now);
}
