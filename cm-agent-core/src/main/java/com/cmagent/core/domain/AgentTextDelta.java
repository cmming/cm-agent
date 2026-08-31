package com.cmagent.core.domain;

/** 可与最终消息块关联的安全文本增量。 */
public record AgentTextDelta(String replyId, String blockId, String delta) {
    public AgentTextDelta {
        replyId = replyId == null || replyId.isBlank() ? null : replyId;
        blockId = blockId == null || blockId.isBlank() ? null : blockId;
        if (delta == null) {
            throw new IllegalArgumentException("delta 不能为空");
        }
    }
}
