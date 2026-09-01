package com.cmagent.core.domain;

/**
 * 可与最终消息块关联的安全文本增量。
 *
 * <p>{@code replyId} 与 {@code blockId} 允许为空，以兼容无法提供原生事件标识的旧 Runtime；{@code delta}
 * 必须已通过上层脱敏后才能离开 Runtime 边界并发送给 SSE 客户端。</p>
 *
 * @param replyId 关联的回复消息标识；旧 Runtime 无法提供时为 {@code null}
 * @param blockId 关联的内容块标识；旧 Runtime 无法提供时为 {@code null}
 * @param delta 已脱敏的文本增量，不能为 {@code null}；旧 Runtime 空输出不会构造该对象
 */
public record AgentTextDelta(String replyId, String blockId, String delta) {
    public AgentTextDelta {
        replyId = replyId == null || replyId.isBlank() ? null : replyId;
        blockId = blockId == null || blockId.isBlank() ? null : blockId;
        if (delta == null) {
            throw new IllegalArgumentException("delta 不能为空");
        }
    }
}
