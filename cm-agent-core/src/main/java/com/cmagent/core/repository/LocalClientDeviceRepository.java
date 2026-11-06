package com.cmagent.core.repository;

import com.cmagent.core.domain.LocalClientChallenge;
import com.cmagent.core.domain.LocalClientDevice;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 持久化原主体的设备绑定及一次性持钥挑战。
 *
 * <p>仓储输入的租户和主体必须来自已认证上下文或可信设备记录；实现必须逐字比较原主体，
 * 不得信任请求体覆盖租户。带事务说明的方法由服务端共享短事务及严格审计共同调用。</p>
 */
public interface LocalClientDeviceRepository {
    /**
     * 按全局唯一设备标识读取挑战签发所需的可信归属。
     *
     * <p>该入口只供未登录客户端申请短期挑战使用；调用方不得把返回设备当成已认证主体，
     * 后续请求仍须验证设备私钥、挑战单次消费和运行授权。</p>
     *
     * @param deviceId 客户端持有并声明的设备标识
     * @return 设备记录；不存在时为空
     */
    Optional<LocalClientDevice> findAnyById(UUID deviceId);

    /**
     * 以初始活动版本登记一台已完成双端确认的设备。
     *
     * @param device 已完成配对验证的设备记录
     * @return 新登记成功时为 {@code true}；标识已存在时为 {@code false}
     */
    boolean insert(LocalClientDevice device);

    /**
     * 读取原主体拥有的设备，跨租户和跨主体均返回空。
     *
     * @param tenantId 可信租户
     * @param ownerId 可信原主体
     * @param deviceId 设备标识
     * @return 匹配的设备记录
     */
    Optional<LocalClientDevice> find(UUID tenantId, String ownerId, UUID deviceId);

    /**
     * 列出原主体的全部设备，包括已撤销记录。
     *
     * @param tenantId 可信租户
     * @param ownerId 原主体标识
     * @return 按创建时间倒序的设备
     */
    List<LocalClientDevice> listByOwner(UUID tenantId, String ownerId);

    /**
     * 条件撤销设备；撤销后对应挑战不得继续使用。
     *
     * @param tenantId 可信租户
     * @param ownerId 可信原主体
     * @param deviceId 设备标识
     * @param expectedVersion 原活动版本
     * @param now 服务端时间
     * @return 本次赢得撤销竞争时为 {@code true}
     */
    boolean revoke(UUID tenantId, String ownerId, UUID deviceId, long expectedVersion, Instant now);

    /**
     * 为仍处于活动状态的设备登记一条服务端生成的挑战。
     *
     * @param challenge 绑定可信设备归属的短期随机挑战
     * @return 已登记且设备仍有效时为 {@code true}
     */
    boolean insertChallenge(LocalClientChallenge challenge);

    /**
     * 查找尚未消费且在服务端时间内有效的挑战。
     *
     * <p>返回值不能作为消费凭证；验签成功后仍需调用 {@link #consumeChallenge} 竞争一次性使用权。</p>
     *
     * @param tenantId 可信租户
     * @param ownerId 可信原主体
     * @param deviceId 可信设备标识
     * @param challengeId 挑战标识
     * @param now 服务端时间
     * @return 未消费且未过期的挑战
     */
    Optional<LocalClientChallenge> findChallenge(UUID tenantId, String ownerId, UUID deviceId,
                                                  UUID challengeId, Instant now);

    /**
     * 原子消费经调用方完成验签的挑战，同时确认设备未撤销且挑战仍未到期。
     *
     * <p>实现必须与设备撤销竞争，并保证同一挑战最多成功消费一次；调用方应将成功消费、
     * 后续派发登记和严格审计放入同一短事务，不能仅凭先前查询结果派发。</p>
     *
     * @param tenantId 可信租户
     * @param ownerId 可信原主体
     * @param deviceId 已完成持钥验证的设备
     * @param challengeId 已完成验签的挑战
     * @param now 服务端时间
     * @return 本次获得单次消费权时为 {@code true}
     */
    boolean consumeChallenge(UUID tenantId, String ownerId, UUID deviceId, UUID challengeId, Instant now);
}
