package com.cmagent.core.repository;

import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunPageRequest;
import com.cmagent.core.domain.RunStatus;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 定义 Agent 运行记录的创建、终态更新、查询和游标分页契约。
 */
public interface RunRepository {
    /**
     * Persists a run only when {@code run.tenantId()} matches {@code tenantId}.
      *
      * @param tenantId 当前租户标识
      * @param run 当前运行记录
     */
    RunRecord save(UUID tenantId, RunRecord run);

    /**
     * Completes only a matching {@link RunStatus#RUNNING} record in the supplied tenant.
      *
      * @param tenantId 当前租户标识
      * @param runId 目标运行标识
      * @param status 目标运行状态
      * @param output 模型或工具输出
      * @param errorMessage 已控制敏感信息的错误说明
      * @param finishedAt 流程完成时间
     */
    RunRecord complete(
            UUID tenantId,
            UUID runId,
            RunStatus status,
            String output,
            String errorMessage,
            Instant finishedAt
    );

    /**
     * 按租户、Agent 和运行标识查询唯一运行记录。
      *
      * @param tenantId 当前租户标识
      * @param agentId 目标 Agent 标识
      * @param runId 目标运行标识
     */
    Optional<RunRecord> findByTenantAndAgentAndId(UUID tenantId, UUID agentId, UUID runId);

    /**
     * Lists runs in {@code startedAt DESC, id DESC} order using a validated page request. Implementations
     * return only rows with {@code (startedAt, id)} strictly less than the non-null cursor tuple.
      *
      * @param tenantId 当前租户标识
      * @param agentId 目标 Agent 标识
      * @param pageRequest 游标位置和页面容量
     */
    List<RunRecord> listByTenantAndAgent(UUID tenantId, UUID agentId, RunPageRequest pageRequest);

    /**
     * 构造与数据库游标查询一致的运行记录倒序比较器。
     *
     * @return 先按开始时间、再按标识倒序排列的比较器
     */
    static Comparator<RunRecord> keysetOrder() {
        return Comparator.comparing(RunRecord::startedAt)
                .reversed()
                .thenComparing(RunRecord::id, (left, right) -> compareIdsByDatabaseOrder(right, left));
    }

    /**
     * Compares UUIDs in the canonical lowercase representation stored in {@code runs.id CHAR(36)}.
      *
      * @param left 参与比较的左侧值
      * @param right 参与比较的右侧值
     */
    static int compareIdsByDatabaseOrder(UUID left, UUID right) {
        return left.toString().compareTo(right.toString());
    }

    /**
     * 判断运行记录是否严格位于给定复合游标之后的下一页范围内。
      *
     * @param run 当前运行记录
     * @param pageRequest 游标位置和页面容量
     * @return 记录应进入下一页时返回 {@code true}
     */
    static boolean isStrictlyBeforeCursor(RunRecord run, RunPageRequest pageRequest) {
        Objects.requireNonNull(run, "run 不能为空");
        Objects.requireNonNull(pageRequest, "pageRequest 不能为空");
        if (pageRequest.beforeStartedAt() == null) {
            return true;
        }
        int startedAtComparison = run.startedAt().compareTo(pageRequest.beforeStartedAt());
        return startedAtComparison < 0
                || (startedAtComparison == 0 && compareIdsByDatabaseOrder(run.id(), pageRequest.beforeId()) < 0);
    }
}
