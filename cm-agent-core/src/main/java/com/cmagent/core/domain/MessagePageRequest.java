package com.cmagent.core.domain;

/**
 * 会话消息按 {@code sequence} 正序读取的分页参数。
 *
 * <p>{@code afterSequence} 是排他游标，使用零表示从会话第一条消息开始；消息时间戳不参与本接口的排序，
 * 以避免并发写入时的展示时间相同影响回放顺序。</p>
 */
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
