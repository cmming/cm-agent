package com.cmagent.core.repository;

import com.cmagent.core.domain.ClientProject;
import com.cmagent.core.domain.LocalProjectPageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 已配对设备项目的 tenant/owner 隔离仓储。 */
public interface ClientProjectRepository {
    /** @return 当前主体的项目，不存在和不可见均返回空 */
    Optional<ClientProject> find(UUID tenantId, String ownerId, UUID projectId);

    /** 在调用方短事务中锁定项目行；内存实现可等同普通读取。 */
    default Optional<ClientProject> lock(UUID tenantId, String ownerId, UUID projectId) {
        return find(tenantId, ownerId, projectId);
    }

    /**
     * 在租户和 owner 条件先于 LIMIT 的前提下分页。
     *
     * @param tenantId 可信租户
     * @param ownerId 精确原主体
     * @param page 稳定 updatedAt/id 游标
     * @param includeArchived 是否包含归档项目
     * @return 当前页面
     */
    List<ClientProject> list(UUID tenantId, String ownerId, LocalProjectPageRequest page,
            boolean includeArchived);

    /** @return 同一设备上同一物理根已存在的项目，用于幂等登记 */
    Optional<ClientProject> findByRoot(UUID tenantId, String ownerId, UUID deviceId,
            String rootIdentity);

    /** @return 首次插入成功时为 true；冲突不覆盖现有项目 */
    boolean insert(ClientProject project);

    /** @return 条件重命名成功时为 true */
    boolean rename(UUID tenantId, String ownerId, UUID projectId, long expectedVersion,
            String name, Instant now);

    /** @return 条件归档成功时为 true；不删除设备目录 */
    boolean archive(UUID tenantId, String ownerId, UUID projectId, long expectedVersion,
            Instant now);
}
