package com.cmagent.core.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 设备项目 Run 的持久执行归属快照及单调防护代次。
 *
 * <p>本类型只定义状态不变量，不提供跨进程互斥或授权。调用方必须从数据库取得可信时间，
 * 并在同一事务中以租户、Run、原主体、实例和代次条件更新；不能使用浏览器或模型传入的时间、
 * 主体或代次作为可信值。数据库条件写失败时必须丢弃本快照，禁止据此派发副作用。</p>
 *
 * @param tenantId 可信认证上下文中的租户标识
 * @param runId 与持久设备项目来源绑定的 Run 标识
 * @param ownerId 原 Run 发起主体，必须来自服务端持久化记录
 * @param instanceId 当前持有执行权或恢复权的 Server 实例随机标识
 * @param epoch 从一开始单调递增的防护代次
 * @param phase 执行、只读恢复或已关闭状态
 * @param expiresAt 数据库可信时间下的归属期限
 */
public record ClientProjectRunOwnership(UUID tenantId, UUID runId, String ownerId,
        UUID instanceId, long epoch, Phase phase, Instant expiresAt) {
    /** 执行归属阶段；恢复阶段不得重新派发原副作用。 */
    public enum Phase {
        /** 允许当前代次执行已授权的新操作。 */
        EXECUTING,
        /** 前代归属过期后的对账阶段，只允许受限停止、状态查询和安全收敛。 */
        RECOVERING,
        /** 归属已关闭，不允许续期或继续执行。 */
        CLOSED
    }

    /** 校验身份、代次与期限；不能把无效快照降级为默认归属。 */
    public ClientProjectRunOwnership {
        Objects.requireNonNull(tenantId, "租户不能为空");
        Objects.requireNonNull(runId, "运行不能为空");
        Objects.requireNonNull(instanceId, "实例不能为空");
        Objects.requireNonNull(phase, "归属阶段不能为空");
        Objects.requireNonNull(expiresAt, "归属期限不能为空");
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 200 || epoch < 1) {
            throw new IllegalArgumentException("运行归属主体或代次无效");
        }
    }

    /**
     * 根据可信来源和数据库时间构造首代执行归属。
     * @param binding 已固定并持久化的设备项目 Run 来源
     * @param instanceId 本进程生成的随机实例标识
     * @param databaseNow 同一事务中读取的数据库时间
     * @param duration 一秒至五分钟的租期
     * @return 尚需在同一事务中唯一插入的首代归属
     */
    public static ClientProjectRunOwnership initial(ClientProjectBinding binding, UUID instanceId,
            Instant databaseNow, Duration duration) {
        Objects.requireNonNull(binding, "运行来源不能为空");
        if (binding.runId() == null) throw new IllegalArgumentException("必须提供运行来源");
        return new ClientProjectRunOwnership(binding.tenantId(), binding.runId(), binding.ownerId(),
                instanceId, 1, Phase.EXECUTING, deadline(databaseNow, duration));
    }

    /**
     * 判断可信实例与代次是否仍可执行新副作用。
     *
     * <p>仅作为事务内的附加判断；必须与数据库条件写和副作用许可原子提交，不能以 JVM
     * 内判断替代数据库锁或对已领取任务的隔离。</p>
     * @param instance 当前服务端实例标识
     * @param generation 调用链持有的防护代次
     * @param databaseNow 同一事务中读取的数据库时间
     * @return 仅执行阶段、身份和代次一致且未到期时为 true
     */
    public boolean permitsExecution(UUID instance, long generation, Instant databaseNow) {
        return phase == Phase.EXECUTING && current(instance, generation, databaseNow);
    }

    /**
     * 判断当前实例是否仍持有受限恢复权；不能据此重放原命令或文件变更。
     * @param instance 当前服务端实例标识
     * @param generation 恢复工作持有的防护代次
     * @param databaseNow 同一事务中读取的数据库时间
     * @return 仅恢复阶段、身份和代次一致且未到期时为 true
     */
    public boolean permitsRecovery(UUID instance, long generation, Instant databaseNow) {
        return phase == Phase.RECOVERING && current(instance, generation, databaseNow);
    }

    /**
     * 为仍有效的归属计算续期快照。
     * @param instance 当前服务端实例标识
     * @param generation 当前持有的防护代次
     * @param databaseNow 同一事务中的数据库时间
     * @param duration 有界租期，一秒至五分钟
     * @return 保持原身份、代次和阶段的新快照
     * @throws IllegalStateException 身份不符、过期、关闭或期限倒退时拒绝
     */
    public ClientProjectRunOwnership renew(UUID instance, long generation,
            Instant databaseNow, Duration duration) {
        if (phase == Phase.CLOSED || !current(instance, generation, databaseNow)) {
            throw new IllegalStateException("运行归属已失效，不能续期");
        }
        Instant next = deadline(databaseNow, duration);
        if (next.isBefore(expiresAt)) throw new IllegalStateException("续期不能缩短归属期限");
        return new ClientProjectRunOwnership(tenantId, runId, ownerId, instanceId, epoch, phase, next);
    }

    /**
     * 将已到期的执行或恢复归属推进到新的恢复代次。
     *
     * <p>此转换不证明外部进程已退出，也不允许重放；已派发或未知任务仍须独立隔离和对账。
     * 多个实例竞争时只有数据库条件写成功者可以使用返回值。</p>
     * @param nextInstance 新恢复实例的随机标识
     * @param databaseNow 同一事务中的数据库时间
     * @param duration 有界租期，一秒至五分钟
     * @return 代次加一且处于恢复阶段的新快照
     * @throws IllegalStateException 当前归属未到期、已经关闭或新旧实例相同
     * @throws ArithmeticException 代次耗尽时拒绝回绕
     */
    public ClientProjectRunOwnership takeOver(UUID nextInstance, Instant databaseNow, Duration duration) {
        Objects.requireNonNull(nextInstance, "恢复实例不能为空");
        Objects.requireNonNull(databaseNow, "数据库时间不能为空");
        if (phase == Phase.CLOSED || expiresAt.isAfter(databaseNow) || instanceId.equals(nextInstance)) {
            throw new IllegalStateException("运行归属尚未到期、已经关闭或实例未变化");
        }
        return new ClientProjectRunOwnership(tenantId, runId, ownerId, nextInstance,
                Math.addExact(epoch, 1), Phase.RECOVERING, deadline(databaseNow, duration));
    }

    private boolean current(UUID instance, long generation, Instant databaseNow) {
        Objects.requireNonNull(databaseNow, "数据库时间不能为空");
        return instanceId.equals(instance) && epoch == generation && expiresAt.isAfter(databaseNow);
    }

    private static Instant deadline(Instant databaseNow, Duration duration) {
        Objects.requireNonNull(databaseNow, "数据库时间不能为空");
        if (duration == null || duration.compareTo(Duration.ofSeconds(1)) < 0
                || duration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("运行归属租期必须在一秒至五分钟内");
        }
        return databaseNow.plus(duration);
    }

    /** 不向普通日志输出主体标识。 */
    @Override public String toString() {
        return "ClientProjectRunOwnership[runId=" + runId + ",epoch=" + epoch + ",phase=" + phase + "]";
    }
}
