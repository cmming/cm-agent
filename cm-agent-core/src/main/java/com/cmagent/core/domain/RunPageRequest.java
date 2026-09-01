package com.cmagent.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 已按容量验证的运行记录游标分页请求，排序语义为 {@code startedAt DESC, id DESC}。
 *
 * <p>{@code beforeStartedAt} 与 {@code beforeId} 组成复合游标，必须同时存在或同时缺失；
 * 单独用时间戳定位会在同一开始时间的多条运行之间产生分页边界歧义，因此不被允许。</p>
 *
 * @param limit 单页最大返回数量，限定 1 到 100
 * @param beforeStartedAt 运行游标中的开始时间；首页请求为 {@code null}
 * @param beforeId 复合游标中的上一条记录标识；首页请求为 {@code null}
 */
public record RunPageRequest(int limit, Instant beforeStartedAt, UUID beforeId) {
    /**
     * 校验运行记录分页容量及复合游标的完整性。
     *
     * @param limit 单页最大返回数量
     * @param beforeStartedAt 运行游标中的开始时间；首页请求为 {@code null}
     * @param beforeId 复合游标中的上一条记录标识；首页请求为 {@code null}
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
