package com.cmagent.core.repository;

import com.cmagent.core.domain.WorkspaceBinding;
import java.util.Optional;
import java.util.UUID;

/**
 * 工作区选择的租户隔离存储。正文由原加密检查点管理，本仓储只保存长期可追溯元数据。
 * 调用方必须在同一会话锁和短事务中完成空会话检查、条件写入及首发 Run 创建。
 */
public interface WorkspaceSelectionRepository {
    /** @return 当前租户及 Agent 的会话绑定，不可见时为空 */
    Optional<WorkspaceBinding> conversation(UUID tenantId, UUID agentId, UUID conversationId);
    /** @return 当前租户及 Agent 的 Run 选择，不可见时为空 */
    Optional<WorkspaceBinding> run(UUID tenantId, UUID agentId, UUID runId);
    /**
     * 条件写入会话绑定；expectedVersion=0 仅允许首次创建，冲突返回 false。
     * @param binding 已从可信身份生成的元数据
     * @param expectedVersion 调用方最后读取的版本
     * @return 是否成功，false 时不得继续创建 Run
     */
    boolean saveConversation(WorkspaceBinding binding, long expectedVersion);
    /** 新会话持久化失败时补偿选择行；必须匹配 tenant、Agent、资源及首次版本，不删除后续更新。 */
    void abandonConversation(WorkspaceBinding binding);
    /** 固定 Run 选择，仅允许新增；重复或存储失败抛出异常，不能覆盖原 Run。 */
    void saveRun(WorkspaceBinding binding);

    /** @return IMPORTED只对绑定owner且原会话创建者可见；其他来源不改变旧权限 */
    default boolean visibleConversation(com.cmagent.core.domain.Conversation value, String principalId) {
        var binding = conversation(value.tenantId(), value.agentId(), value.id()).orElse(null);
        return binding == null || binding.selection().mode() != com.cmagent.core.domain.WorkspaceSelection.Mode.IMPORTED
                || principalId.equals(binding.principalId()) && principalId.equals(value.createdBy());
    }

    /**
     * @param value 可信运行记录
     * @param conversation 由可信仓储findByRun取得的关联会话，无会话时为空，不能从HTTP接收
     * @param principalId 认证主体
     * @return 固定来源或派生会话使用IMPORTED时，必须同时满足各来源owner及原运行发起人
     */
    default boolean visibleRun(com.cmagent.core.domain.RunRecord value, com.cmagent.core.domain.Conversation conversation, String principalId) {
        var binding = run(value.tenantId(), value.agentId(), value.id()).orElse(null);
        boolean importedRun = binding != null && binding.selection().mode() == com.cmagent.core.domain.WorkspaceSelection.Mode.IMPORTED;
        if (importedRun && (!principalId.equals(binding.principalId()) || !principalId.equals(value.principalId()))) return false;
        if (conversation == null) return true;
        if (!visibleConversation(conversation, principalId)) return false;
        var source = conversation(conversation.tenantId(), conversation.agentId(), conversation.id()).orElse(null);
        return source == null || source.selection().mode() != com.cmagent.core.domain.WorkspaceSelection.Mode.IMPORTED
                || principalId.equals(value.principalId());
    }
}
