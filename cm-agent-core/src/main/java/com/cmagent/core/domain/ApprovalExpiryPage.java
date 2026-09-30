package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 仅供服务端系统任务使用的过期审批升序游标。
 * @param cutoff 服务端 UTC 截止时间，等于该时间也过期
 * @param afterExpiresAt 上一项截止时间，与 afterId 同时为空表示第一页
 * @param afterId 上一项标识，按 UUID 规范字符串比较
 * @param limit 数量上限，范围 1～100
 */
public record ApprovalExpiryPage(Instant cutoff, Instant afterExpiresAt, UUID afterId, int limit) {
    public ApprovalExpiryPage {
        Objects.requireNonNull(cutoff, "扫描截止时间不能为空");
        if ((afterExpiresAt == null) != (afterId == null) || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("过期扫描游标不完整或批次大小不在 1～100 之间");
        }
    }
}
