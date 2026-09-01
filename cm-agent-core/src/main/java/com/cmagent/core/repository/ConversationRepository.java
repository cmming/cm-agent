package com.cmagent.core.repository;

import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationPageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 会话元数据的租户隔离存储契约。 */
public interface ConversationRepository {
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
