package com.cmagent.core.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * 按游标读取审计事件的分页请求，排序语义为 {@code createdAt DESC, id DESC}。
 *
 * <p>{@code beforeCreatedAt} 与 {@code beforeId} 组成复合游标，必须同时存在或同时缺失；
 * 单独使用时间戳会在同一时刻的多条事件之间产生分页边界歧义，因此不被支持。</p>
 *
 * @param limit 单页最大返回数量，限定 1 到 100
 * @param beforeCreatedAt 审计游标中的创建时间；首页请求为 {@code null}
 * @param beforeId 复合游标中的上一条记录标识；首页请求为 {@code null}
 */
public record AuditPageRequest(int limit, Instant beforeCreatedAt, UUID beforeId) {
    /**
     * 校验审计分页容量及复合游标的完整性。
     *
     * @param limit 单页最大返回数量
     * @param beforeCreatedAt 审计游标中的创建时间；首页请求为 {@code null}
     * @param beforeId 复合游标中的上一条记录标识；首页请求为 {@code null}
     */
    public AuditPageRequest {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
        if ((beforeCreatedAt == null) != (beforeId == null)) {
            throw new IllegalArgumentException("beforeCreatedAt 与 beforeId 必须同时为空或同时非空");
        }
    }
}
