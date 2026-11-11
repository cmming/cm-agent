package com.cmagent.core.repository;

import com.cmagent.core.domain.ClientProjectRunOwnership;
import com.cmagent.core.domain.ClientProjectBinding;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 设备项目 Run 的数据库归属和防护代次契约。
 *
 * <p>该接口不授权调用者自行取得其他租户或主体的归属。所有方法使用可信服务端上下文，
 * 实现必须使用数据库时间和行级条件更新，禁止依赖调用方或机器时钟判定租期。接口本身尚不
 * 替代任务领取、审批、回报或 Run 终态写入的事务栅栏。</p>
 */
public interface ClientProjectRunOwnershipRepository {
    /**
     * 在新 Run 与来源绑定的同一事务中插入首代执行归属。
     * @param binding 服务端已验证的 Run 不可变来源
     * @param instanceId 本进程生成的随机实例标识
     * @param duration 有界租期，一秒至五分钟
     * @return 首次插入成功的首代归属；已存在或来源不匹配时为空
     */
    Optional<ClientProjectRunOwnership> insertInitial(ClientProjectBinding binding, UUID instanceId,
            Duration duration);

    /**
     * 在调用方事务中锁定并校验当前执行归属。
     *
     * <p>返回值仅在当前事务内有效；调用方必须在释放行锁前完成被保护的条件写。过期、阶段
     * 不匹配、租户或主体不匹配时返回空，不能降级为允许。</p>
     * @param tenantId 已认证租户
     * @param ownerId Run 原发起主体
     * @param runId 已验证来源的 Run
     * @param instanceId 本进程启动时生成的随机实例标识
     * @param epoch 当前执行片段持有的持久防护代次
     * @return 可信数据库时间下仍有效的执行归属，否则为空
     */
    Optional<ClientProjectRunOwnership> lockExecution(UUID tenantId, String ownerId, UUID runId,
            UUID instanceId, long epoch);

    /**
     * 在调用方事务中锁定并校验受限恢复归属。
     *
     * <p>恢复权仅供原任务的查询、停止和安全收敛；它不重新授权原文件变更、命令或模型调用。
     * 与外部副作用有关的未知状态须保留隔离，行锁释放后不能继续使用此快照。</p>
     * @param tenantId 已认证租户
     * @param ownerId Run 原发起主体
     * @param runId 已验证来源的 Run
     * @param instanceId 当前恢复实例标识
     * @param epoch 当前恢复代次
     * @return 可信数据库时间下仍有效的恢复归属，否则为空
     */
    Optional<ClientProjectRunOwnership> lockRecovery(UUID tenantId, String ownerId, UUID runId,
            UUID instanceId, long epoch);

    /**
     * 仅在相同实例、代次、阶段和租期仍有效时延长期限。
     * @param tenantId 已认证租户
     * @param ownerId Run 原发起主体
     * @param runId 已验证来源的 Run
     * @param instanceId 本进程随机实例标识
     * @param epoch 当前代次
     * @param duration 有界租期，一秒至五分钟
     * @return 条件更新后的归属；已过期、被接管或不存在时为空
     */
    Optional<ClientProjectRunOwnership> renew(UUID tenantId, String ownerId, UUID runId,
            UUID instanceId, long epoch, Duration duration);

    /**
     * 有条件地取得过期归属的下一恢复代次。
     *
     * <p>此方法必须和前代已派发任务转为未知、未派发许可撤销及严格审计放在同一个事务；
     * 返回恢复权不代表已静止，也不授权执行旧命令。</p>
     * @param tenantId 已认证租户
     * @param ownerId Run 原发起主体
     * @param runId 已验证来源的 Run
     * @param expectedEpoch 可信仓储读出的原代次
     * @param nextInstance 新实例随机标识
     * @param duration 有界租期，一秒至五分钟
     * @return 仅数据库确认已过期并赢得条件竞争时返回恢复归属
     */
    Optional<ClientProjectRunOwnership> takeOverForRecovery(UUID tenantId, String ownerId,
            UUID runId, long expectedEpoch, UUID nextInstance, Duration duration);

    /** 恢复代次提交之前必须完成的旧许可隔离和审计；异常必须使整个事务回滚。 */
    @FunctionalInterface
    interface RecoveryBarrier {
        /**
         * 在持有归属行锁的同一事务中执行隔离和审计。
         * @param previous 经锁定且已过期的原归属
         * @param next 将要接管的受限恢复归属
         */
        void quarantineAndAudit(ClientProjectRunOwnership previous, ClientProjectRunOwnership next);
    }
}
