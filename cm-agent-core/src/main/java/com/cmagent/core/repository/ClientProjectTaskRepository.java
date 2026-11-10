package com.cmagent.core.repository;

import com.cmagent.core.domain.ClientProjectTask;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 设备项目任务的持久队列和条件状态仓储。 */
public interface ClientProjectTaskRepository {
    /** @return 唯一插入成功时为 true */
    boolean insert(ClientProjectTask task);

    /**
     * 在设备行锁内插入目录选择任务，保证同一设备最多一个未决选择窗口。
     *
     * <p>默认实现只用于不提供并发锁的兼容仓储；JDBC 实现必须覆盖为设备行锁加活动任务检查，
     * 防止两个请求在分别检查后同时插入。</p>
     *
     * @param task 必须为 PENDING 的 SELECT_ROOT 任务
     * @return 插入成功时为 true；设备已存在未决选择窗口时返回 false
     */
    default boolean insertSelectionExclusive(ClientProjectTask task) {
        if (task.kind() != ClientProjectTask.Kind.SELECT_ROOT) {
            throw new IllegalArgumentException("独占插入仅用于目录选择任务");
        }
        return insert(task);
    }

    /** @return 仅当前 tenant/owner/device 可见的任务 */
    Optional<ClientProjectTask> find(UUID tenantId, String ownerId, UUID deviceId, UUID taskId);

    /** @return 当前主体通过浏览器查询任务时可访问的任务 */
    Optional<ClientProjectTask> findForOwner(UUID tenantId, String ownerId, UUID taskId);

    /** @return 项目中仍有待领取、已领取或结果未知任务时为 true */
    default boolean hasUnresolvedForProject(UUID tenantId, String ownerId, UUID projectId) {
        return false;
    }

    /** @return 指定命令仍有待领取、已领取或 UNKNOWN 任务时为 true */
    default boolean hasUnresolvedForCommand(UUID tenantId, String ownerId, UUID commandId) {
        return false;
    }

    /** 关闭不再接受回报的命令任务；已领取任务按 UNKNOWN 收口。 */
    default int closeOpenForCommand(UUID tenantId, String ownerId, UUID commandId,
            Instant now) {
        return 0;
    }

    /**
     * 原子领取最早且未过期的任务。实现必须在同一事务内把 PENDING 推进为 DISPATCHED。
     *
     * @return 本次唯一领取到的任务
     */
    Optional<ClientProjectTask> claimNext(UUID tenantId, String ownerId, UUID deviceId,
            Instant now);

    /** 将已领取任务推进到成功、失败或未知终态。 */
    boolean complete(UUID tenantId, String ownerId, UUID deviceId, UUID taskId,
            long expectedVersion, ClientProjectTask.State state, String encryptedResult,
            String safeError, Instant now);

    /** 将未领取任务取消或将已领取任务标为未知，不自动重派。 */
    boolean close(UUID tenantId, String ownerId, UUID deviceId, UUID taskId,
            long expectedVersion, ClientProjectTask.State state, String safeError, Instant now);
}
