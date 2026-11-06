package com.cmagent.core.repository;

import com.cmagent.core.domain.LocalProjectBinding;
import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationPageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 项目来源与会话/Run的不可变关联，调用方必须和来源见证在同一短事务保存。
 * 新协议4的HTTP/Runtime接线前不得将这些关联作为旧DEFAULT读取。
 */
public interface LocalProjectBindingRepository {
    /**
     * 项目锁下归档前检查已知绑定的所有活动Run；损坏来源头不能使活动绑定消失。
     * @param tenantId 可信租户
     * @param ownerId 认证原主体，绑定owner须逐字匹配
     * @param projectId 已锁定的固定项目
     * @return 任一绑定Run仍为RUNNING/WAITING_APPROVAL/CANCELLING时true，未知实现拒绝而非误报无活动
     */
    default boolean hasActiveRuns(UUID tenantId, String ownerId, UUID projectId) {
        throw new UnsupportedOperationException("当前绑定仓储未实现归档活动运行检查");
    }
    /**
     * 冷启动恢复专用的内部跨租户发现；不得接入HTTP，节点只能来自已持锁的部署配置。
     * @param nodeId 固定本机节点，精确比较
     * @param limit 单批1至100项；所有来源、owner、Agent和活动态条件须先于LIMIT
     * @return 仅NORMAL且协议4、RUNNING/CANCELLING的完整固定绑定，不读取根或载荷
     */
    default List<LocalProjectBinding> findInterruptedRuns(String nodeId, int limit) {
        throw new UnsupportedOperationException("当前绑定仓储未实现冷启动发现");
    }
    /** @return 当前主体的会话绑定，不存在/不可见均空 */
    Optional<LocalProjectBinding> conversation(UUID tenantId, String ownerId, UUID conversationId);
    /** @return 当前主体的Run绑定，不存在/不可见均空 */
    Optional<LocalProjectBinding> run(UUID tenantId, String ownerId, UUID runId);
    /** 会话绑定只新增；重复或归属不符返回false，不能覆盖；写入与来源头由调用方共享事务。 */
    boolean insertConversation(LocalProjectBinding binding);
    /** Run绑定只新增；须与原会话/Run可信归属一致，并在同一事务持久化来源头。 */
    boolean insertRun(LocalProjectBinding binding);

    /**
     * 项目专用会话页；tenant/精确owner/项目及来源4一致性必须在LIMIT之前过滤。
     * @param tenantId 可信租户
     * @param ownerId 认证主体
     * @param projectId 已完成私有项目授权的标识
     * @param page 稳定updatedAt/id复合游标
     * @return 项目内独立会话，不返回文件正文或根路径
     */
    default List<Conversation> listConversations(UUID tenantId, String ownerId, UUID projectId, ConversationPageRequest page) {
        throw new UnsupportedOperationException("当前仓储不支持项目会话分页");
    }
}

