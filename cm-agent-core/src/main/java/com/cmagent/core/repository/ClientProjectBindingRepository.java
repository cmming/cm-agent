package com.cmagent.core.repository;

import com.cmagent.core.domain.ClientProjectBinding;
import com.cmagent.core.domain.Conversation;
import com.cmagent.core.domain.ConversationPageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 已配对设备项目的会话和 Run 不可变来源仓储。 */
public interface ClientProjectBindingRepository {
    /** @return 当前主体的会话绑定 */
    Optional<ClientProjectBinding> conversation(UUID tenantId, String ownerId, UUID conversationId);

    /** @return 当前主体的 Run 绑定 */
    Optional<ClientProjectBinding> run(UUID tenantId, String ownerId, UUID runId);

    /** @return 会话绑定与协议 5 同事务新增成功时为 true */
    boolean insertConversation(ClientProjectBinding binding);

    /** @return Run 绑定与协议 5 同事务新增成功时为 true */
    boolean insertRun(ClientProjectBinding binding);

    /** @return 当前 owner 的项目会话分页 */
    List<Conversation> listConversations(UUID tenantId, String ownerId, UUID projectId,
            ConversationPageRequest page);
}
