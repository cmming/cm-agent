package com.cmagent.core.repository;

import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationPageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 会话元数据的租户隔离存储契约。 */
public interface ConversationRepository {
    /** @return 是否能原子保存并读取独立来源头见证；旧嵌入实现默认不声明支持导入来源 */
    default boolean supportsWorkspaceProtocol() { return false; }

    /**
     * 首次保存会话时原子固定当前来源头见证，不能在保存后另补写而暴露旧默认来源窗口。
     * @param tenantId 可信认证租户
     * @param conversation 已完成认证与归属检查的新会话
     * @param protocol 0为旧三态，3为IMPORTED，不能来自客户端任意协议号
     * @return 已保存会话；旧实现仅兼容0，不得静默保存3
     */
    default Conversation saveWithWorkspaceProtocol(UUID tenantId, Conversation conversation, int protocol) {
        if (protocol != 0 && protocol != 3) throw new IllegalArgumentException("会话工作区协议不合法");
        if (protocol == 3) throw new UnsupportedOperationException("当前仓储未实现导入会话来源见证");
        return save(tenantId, conversation);
    }

    /**
     * @param tenantId 可信租户
     * @param agentId 已核实的Agent
     * @param conversationId 目标会话
     * @return 当前持久来源见证；旧实现只兼容存在会话的0，缺失必须抛出而不能伪装为旧来源
     */
    default int workspaceProtocol(UUID tenantId, UUID agentId, UUID conversationId) {
        findByTenantAndAgentAndId(tenantId, agentId, conversationId)
                .orElseThrow(() -> new java.util.NoSuchElementException("会话不存在"));
        return 0;
    }

    /**
     * 宿主必须在同一空会话控制锁、JDBC行锁及短事务内与绑定更新一起调用；本方法不清空消息或改写标题/时间。
     * @param tenantId 可信租户
     * @param agentId 已核实的Agent
     * @param conversationId 已校验为空且可改绑的会话
     * @param protocol 新当前来源头见证，只能为0或3
     */
    default void updateWorkspaceProtocol(UUID tenantId, UUID agentId, UUID conversationId, int protocol) {
        if (protocol != 0 && protocol != 3) throw new IllegalArgumentException("会话工作区协议不合法");
        if (protocol == 3) throw new UnsupportedOperationException("当前仓储未实现导入会话来源见证");
        findByTenantAndAgentAndId(tenantId, agentId, conversationId)
                .orElseThrow(() -> new java.util.NoSuchElementException("会话不存在"));
    }

    /**
     * 在调用方短事务内锁定会话，串行化首发与目录选择；JDBC 必须覆盖为行锁。
     * memory/旧嵌入实现由宿主会话锁保护，默认只核对归属。
     * @return 可信范围内的会话，越界或不存在为空
     */
    default Optional<Conversation> lock(UUID tenantId, UUID agentId, UUID conversationId) {
        return findByTenantAndAgentAndId(tenantId, agentId, conversationId);
    }
    /**
     * 按可信 Run 查找其会话；普通单轮 Run 返回空，不能跨租户匹配消息。
     * 旧嵌入实现保持兼容；Server 的 memory/JDBC 实现必须覆盖本方法以复核下载 owner。
     */
    default Optional<Conversation> findByRun(UUID tenantId, UUID runId) { return Optional.empty(); }
    /**
     * 保存新会话。
     *
     * <p>实现必须验证参数 {@code tenantId} 与会话所属租户一致，不能允许调用方借由领域对象
     * 覆盖可信租户边界。</p>
     *
     * @param tenantId 当前可信租户标识
     * @param conversation 待保存的会话
     * @return 已保存的会话
     */
    Conversation save(UUID tenantId, Conversation conversation);

    /**
     * 在租户和 Agent 双重边界内查询会话。
     *
     * <p>不存在、跨租户或属于其他 Agent 时均返回空，避免向上层泄露资源是否存在。</p>
     *
     * @param tenantId 当前可信租户标识
     * @param agentId 会话应归属的 Agent 标识
     * @param conversationId 目标会话标识
     * @return 当前边界内的会话；不匹配时为空
     */
    Optional<Conversation> findByTenantAndAgentAndId(UUID tenantId, UUID agentId, UUID conversationId);

    /**
     * 按最后更新时间和会话标识倒序读取 Agent 的会话页。
     *
     * <p>实现必须将 {@code updatedAt + id} 视为稳定复合游标，避免同一时间戳下分页出现漏项或重复。</p>
     *
     * @param tenantId 当前可信租户标识
     * @param agentId 目标 Agent 标识
     * @param pageRequest 游标和页容量
     * @return 当前页会话，顺序为 {@code updatedAt DESC, id DESC}
     */
    List<Conversation> listByTenantAndAgent(UUID tenantId, UUID agentId, ConversationPageRequest pageRequest);

    /**
     * 在游标排序及LIMIT前过滤IMPORTED的其他owner，原三态保持原权限；principalId须来自认证上下文。
     * 未实现的旧仓储明确拒绝，不能先截断分页再在页面隐藏私有会话。
     * @return 当前可信主体可见的一页会话
     */
    default List<Conversation> listVisible(UUID tenantId, UUID agentId, String principalId, ConversationPageRequest pageRequest) {
        throw new UnsupportedOperationException("当前仓储未实现导入会话可见分页");
    }

    /**
     * 更新会话标题和最后活动时间。
     *
     * <p>该操作是消息追加的可观察副作用；实现必须保留会话的创建者、创建时间和 Agent 归属。</p>
     *
     * @param tenantId 当前可信租户标识
     * @param conversationId 目标会话标识
     * @param title 新标题
     * @param updatedAt 当前活动时间
     * @return 更新后的会话
     * @throws java.util.NoSuchElementException 会话不存在或不属于当前租户时抛出
     */
    Conversation touch(UUID tenantId, UUID conversationId, String title, Instant updatedAt);
}
