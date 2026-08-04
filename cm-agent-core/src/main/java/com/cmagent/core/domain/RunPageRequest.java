package com.cmagent.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Validated keyset page request for runs ordered by {@code startedAt DESC, id DESC}.
 */
public record RunPageRequest(int limit, Instant beforeStartedAt, UUID beforeId) {
    /**
     * 校验运行记录分页容量及复合游标的完整性。
      *
      * @param limit 单页最大返回数量
      * @param beforeStartedAt 运行游标中的开始时间
      * @param beforeId 复合游标中的上一条记录标识
     */
    public RunPageRequest {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
        if ((beforeStartedAt == null) != (beforeId == null)) {
            throw new IllegalArgumentException("beforeStartedAt 与 beforeId 必须同时为空或同时非空");
        }
    }
}
