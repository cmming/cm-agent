package com.cmagent.core.domain;

/** 会话消息按 sequence 正序读取的分页参数。 */
public record MessagePageRequest(int limit, long afterSequence) {
    public MessagePageRequest {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit 必须在 1 到 200 之间");
        }
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence 不能小于 0");
        }
    }
}
