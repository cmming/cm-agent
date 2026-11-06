package com.cmagent.core.repository;

import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.domain.LocalClientPairing;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 已认证浏览器创建、客户端轮询和浏览器确认的短期配对会话合同。
 *
 * <p>配对状态与活动设备创建必须由同一短事务完成；查询不能替代确认竞争。配对编号可在未登录
 * 状态轮询非秘密状态，但不会返回私钥、签名或任何运行数据。</p>
 */
public interface LocalClientPairingRepository {
    /**
     * 保存由认证主体发起且已通过客户端签名验证的待确认配对。
     *
     * @param pairing 绑定可信租户与主体的待确认会话
     * @return 标识冲突时返回 {@code false}
     */
    boolean insertPairing(LocalClientPairing pairing);

    /**
     * 按配对编号读取会话；不同调用方不能通过该查询取得秘密材料。
     *
     * @param pairingId 客户端生成的配对请求标识
     * @return 配对会话
     */
    Optional<LocalClientPairing> findPairing(UUID pairingId);

    /**
     * 原子确认仍在期限内的待确认配对，并创建活动设备。
     *
     * @param tenantId 当前认证主体所属租户
     * @param ownerId 当前认证主体标识
     * @param pairingId 待确认配对标识
     * @param device 由服务端生成的初始活动设备
     * @param now 服务端当前时间
     * @return 本次首次确认成功时为 {@code true}
     */
    boolean confirmPairing(UUID tenantId, String ownerId, UUID pairingId,
                           LocalClientDevice device, Instant now);

    /**
     * 条件终止尚未确认的配对，用于审计失败或用户撤销。
     *
     * @param tenantId 可信租户
     * @param ownerId 原主体
     * @param pairingId 配对标识
     * @param now 终止时间
     * @return 状态从待确认变为过期时为 {@code true}
     */
    boolean expirePairing(UUID tenantId, String ownerId, UUID pairingId, Instant now);
}
