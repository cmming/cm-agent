package com.cmagent.core.repository;

import com.cmagent.core.domain.ClientProjectOperation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 设备项目受保护文件变更的持久意图和条件状态。 */
public interface ClientProjectOperationRepository {
    /** @return 初次 PROPOSED/version=1 插入成功时为 true */
    boolean insert(ClientProjectOperation operation);

    /** @return 精确 tenant/owner 下的操作 */
    Optional<ClientProjectOperation> find(UUID tenantId, String ownerId, UUID operationId);

    /** 仅在调用方短事务中锁定操作行。 */
    default Optional<ClientProjectOperation> lock(UUID tenantId, String ownerId,
            UUID operationId) {
        return find(tenantId, ownerId, operationId);
    }

    /** @return 条件推进成功时的新版本 */
    Optional<ClientProjectOperation> advance(UUID tenantId, String ownerId,
            UUID operationId, long expectedVersion, ClientProjectOperation.State next,
            Instant now);

    /** @return 项目存在 APPLYING 或 UNKNOWN 操作时为 true */
    boolean hasUncertain(UUID tenantId, String ownerId, UUID projectId);
}
