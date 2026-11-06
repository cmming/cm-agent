package com.cmagent.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 私有项目列表的稳定复合游标，owner过滤必须先于LIMIT。
 * @param limit 本页上限，限定1至100
 * @param beforeUpdatedAt 上一条项目更新时间，首页为空
 * @param beforeId 上一条项目标识，必须与时间同时提供
 */
public record LocalProjectPageRequest(int limit, Instant beforeUpdatedAt, UUID beforeId) {
    public LocalProjectPageRequest {
        if (limit < 1 || limit > 100 || (beforeUpdatedAt == null) != (beforeId == null))
            throw new IllegalArgumentException("项目分页参数不合法");
    }
}

