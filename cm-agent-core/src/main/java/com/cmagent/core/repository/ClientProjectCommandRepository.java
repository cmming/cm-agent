package com.cmagent.core.repository;

import com.cmagent.core.domain.ClientProjectCommand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 已配对设备项目固定工具链命令的持久状态仓储。 */
public interface ClientProjectCommandRepository {
    /** @return 初次 PROPOSED/version=1 插入成功时为 true */
    boolean insert(ClientProjectCommand command);

    /** @return 精确 tenant/owner 下的命令 */
    Optional<ClientProjectCommand> find(UUID tenantId, String ownerId, UUID commandId);

    /** 仅在调用方短事务中锁定命令行。 */
    default Optional<ClientProjectCommand> lock(UUID tenantId, String ownerId, UUID commandId) {
        return find(tenantId, ownerId, commandId);
    }

    /** @return 条件推进成功后的新版本 */
    Optional<ClientProjectCommand> update(UUID tenantId, String ownerId, UUID commandId,
            long expectedVersion, ClientProjectCommand next);

    /** @return 仍可能启动、运行或需要人工对账的命令 */
    List<ClientProjectCommand> listUnsettledForRun(UUID tenantId, String ownerId, UUID runId);

    /** @return 指定 Run 内全部命令，用于终态汇总和输出历史 */
    List<ClientProjectCommand> listForRun(UUID tenantId, String ownerId, UUID runId);

    /** @return 项目仍存在活动或 UNKNOWN 命令时为 true */
    boolean hasUnsettledForProject(UUID tenantId, String ownerId, UUID projectId);

    /** @return 指定命令仍存在未完成设备任务时禁止重复创建同一状态任务 */
    default boolean hasUnresolvedTask(UUID tenantId, String ownerId, UUID commandId) {
        return false;
    }

    /** 将已登记停止但长期无法核实的命令收口为 UNKNOWN。 */
    int expireUnsettled(UUID tenantId, String ownerId, UUID runId, Instant cutoff, Instant now);
}
