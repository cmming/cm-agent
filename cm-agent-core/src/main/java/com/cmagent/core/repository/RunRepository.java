package com.cmagent.core.repository;

import com.cmagent.core.domain.RunRecord;
import com.cmagent.core.domain.RunKind;
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
    /** @return 是否实现首次原子来源见证；旧嵌入仓储不得仅声明支持却忽略协议 */
    default boolean supportsWorkspaceProtocol(){return false;}
    /** 使用原发起人和等待态快照原子恢复 RUNNING；停止先赢得竞争时返回 false，不能重放原 Run。 */
    default boolean resumeWaitingApproval(RunRecord snapshot) {
        throw new UnsupportedOperationException("当前仓储不支持原子审批恢复");
    }
    /**
     * 使用可信仓储快照原子登记停止；归属、主体和状态变化时返回 false，不覆盖已完成结果。
     * 旧仓储默认明确拒绝，避免读写分离伪装成原子停止。
     */
    default boolean requestCancellation(RunRecord snapshot) {
        throw new UnsupportedOperationException("当前仓储不支持停止");
    }

    /**
     * 仅在可信租户、Agent、Run、发起人匹配且仍为 WAITING_APPROVAL 时原子收口为 DENIED。
     * 未命中返回 false，禁止随后删除检查点；不会覆盖已恢复的 RUNNING 或终态。
     */
    boolean expireWaitingApproval(RunRecord snapshot, Instant finishedAt);
    /**
     * 仅在 {@code run.tenantId()} 与 {@code tenantId} 一致时保存运行记录。
     *
     * <p>该判等是写入租户边界的唯一闸门：领域对象中的租户字段不可信时（例如反序列化自外部），
     * 实现必须拒绝保存而不是静默改写。</p>
     *
     * @param tenantId 当前租户标识
     * @param run 当前运行记录
     */
    RunRecord save(UUID tenantId, RunRecord run);

    /**
     * 首次插入运行时原子固定工作区协议，后续状态转换不能改变该见证；不能由HTTP指定。
     * @param tenantId 可信租户
     * @param run 服务端新运行
     * @param protocol 0为旧目录/无资料，3为IMPORTED不可降级协议
     * @return 已保存运行；旧嵌入实现只兼容0，不能静默保存3
     */
    default RunRecord saveWithWorkspaceProtocol(UUID tenantId, RunRecord run, int protocol) {
        if (protocol != 0) throw new UnsupportedOperationException("当前仓储未实现导入运行来源见证");
        return save(tenantId, run);
    }

    /** @return 可信Run的持久协议；不存在必须抛出，不能把丢失记录当作旧协议0 */
    default int workspaceProtocol(UUID tenantId, UUID agentId, UUID runId) {
        findByTenantAndAgentAndId(tenantId, agentId, runId).orElseThrow(() -> new java.util.NoSuchElementException("运行不存在"));
        return 0;
    }

    /**
     * 在选择行丢失时仍用messages→Run来源见证保护派生会话，旧三态不增加owner限制。
     * @param conversation 可信会话记录
     * @param principalId 认证主体
     * @return 所有关联IMPORTED运行及原会话创建者是否均匹配当前主体
     */
    default boolean visibleConversationFromWorkspace(com.cmagent.core.domain.Conversation conversation, String principalId) {
        throw new UnsupportedOperationException("当前仓储未实现导入派生会话见证过滤");
    }

    /** 将 RUNNING 运行原子转换为 WAITING_APPROVAL。 */
    RunRecord waitForApproval(UUID tenantId, UUID runId);

    /**
     * 将当前租户内处于非终态的运行记录收口为终态。
     *
     * <p>只有 RUNNING、WAITING_APPROVAL 或 CANCELLING 记录允许完成，防止已终态记录被重复收口或改写历史；
     * 运行不存在、不属于该租户或已终态时的行为由实现定义并有测试覆盖。</p>
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
     * @return 命中时返回运行记录；跨租户或不存在的查询一律返回空，不泄露资源存在性
     */
    Optional<RunRecord> findByTenantAndAgentAndId(UUID tenantId, UUID agentId, UUID runId);

    /**
     * 按 {@code startedAt DESC, id DESC} 排序，使用已验证的游标请求列出运行记录。
     * 实现必须只返回复合游标 {@code (startedAt, id)} 严格之前的记录。
     *
     * <p>排序和判等语义须与 {@link #keysetOrder()}、{@link #compareIdsByDatabaseOrder(UUID, UUID)}
     * 保持一致，使内存分页测试与数据库 keyset 查询行为相同。</p>
     *
     * @param tenantId 当前租户标识
     * @param agentId 目标 Agent 标识
     * @param pageRequest 游标位置和页面容量
     * @return 按游标排序的当前页运行记录
     */
    List<RunRecord> listByTenantAndAgent(UUID tenantId, UUID agentId, RunPageRequest pageRequest);

    /**
     * 在游标/LIMIT之前过滤IMPORTED固定来源及其派生会话的其他owner，原三态保持原权限。
     * principalId只能来自认证上下文；旧仓储不允许通过截断后过滤伪装支持。
     * @return 当前主体可见的NORMAL运行页
     */
    default List<RunRecord> listVisible(UUID tenantId, UUID agentId, String principalId, RunPageRequest pageRequest) {
        throw new UnsupportedOperationException("当前仓储未实现导入运行可见分页");
    }

    /** 按指定运行类型分页查询；试运行只能通过显式类型入口读取。 */
    default List<RunRecord> listByTenantAndAgentAndKind(
            UUID tenantId,
            UUID agentId,
            RunKind kind,
            RunPageRequest pageRequest
    ) {
        Objects.requireNonNull(kind, "kind 不能为空");
        return listByTenantAndAgent(tenantId, agentId, pageRequest).stream()
                .filter(run -> run.kind() == kind)
                .toList();
    }

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
     * 按数据库存储的规范化小写字符串表示比较 UUID，与 {@code runs.id CHAR(36)} 列的排序一致。
     *
     * <p>数据库按字符串而非 UUID 数值序排序，若在内存中用 {@code UUID.compareTo} 会产生
     * 与 SQL 查询不一致的分页顺序；内存分页工具必须复用该方法。</p>
     *
     * @param left 参与比较的左侧值
     * @param right 参与比较的右侧值
     * @return 按字符串序比较的结果
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
