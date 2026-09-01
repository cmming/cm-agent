package com.cmagent.core.repository;

import com.cmagent.core.domain.ConversationMessage;
import com.cmagent.core.domain.ConversationMessageDraft;
import com.cmagent.core.domain.MessagePageRequest;

import java.util.List;
import java.util.UUID;

/** 会话内消息顺序追加和分页读取契约。 */
public interface ConversationMessageRepository {
    /**
     * 追加消息并由实现分配会话内连续序号。
     *
     * <p>同一会话的并发追加必须串行化；调用方只提交草稿，不能自行决定 {@code sequence}。
     * 成功追加还应推进会话最后活动时间。</p>
     *
     * @param tenantId 当前可信租户标识
     * @param draft 不含序号的消息草稿
     * @return 已分配稳定序号的消息
     * @throws java.util.NoSuchElementException 会话不存在或不属于当前租户时抛出
     */
    ConversationMessage append(UUID tenantId, ConversationMessageDraft draft);

    /**
     * 从给定序号之后按正序读取消息。
     *
     * @param tenantId 当前可信租户标识
     * @param conversationId 目标会话标识
     * @param pageRequest 页容量和排他起始序号
     * @return {@code sequence} 严格递增的消息页
     */
    List<ConversationMessage> list(UUID tenantId, UUID conversationId, MessagePageRequest pageRequest);

    /**
     * 读取最近的完整消息窗口。
     *
     * <p>查询可以在存储层按倒序取得数据以利用索引，但返回列表必须恢复为 {@code sequence} 正序，
     * 以便调用方直接构造模型上下文。</p>
     *
     * @param tenantId 当前可信租户标识
     * @param conversationId 目标会话标识
     * @param limit 最大消息数
     * @return 最多 {@code limit} 条、按 {@code sequence} 正序排列的最近消息
     */
    List<ConversationMessage> listRecent(UUID tenantId, UUID conversationId, int limit);
}
