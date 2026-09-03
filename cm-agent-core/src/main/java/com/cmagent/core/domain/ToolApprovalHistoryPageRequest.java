package com.cmagent.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 按 {@code decidedAt DESC, id DESC} 查询已处理审批的稳定复合游标。
 *
 * <p>同一时刻可能有多条决定，必须同时使用时间与标识定位；仓储中的 UUID 应按规范字符串排序，
 * 保持 memory 与数据库 CHAR(36) 的顺序一致。</p>
 *
 * @param limit 单页上限，范围 1 到 100
 * @param beforeDecidedAt 上一页末条决定时间，首页为 {@code null}
 * @param beforeId 上一页末条审批标识，必须与时间同时提供或同时为空
 */
public record ToolApprovalHistoryPageRequest(int limit, Instant beforeDecidedAt, UUID beforeId) {
    public ToolApprovalHistoryPageRequest {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("历史审批 limit 必须在 1 到 100 之间");
        if ((beforeDecidedAt == null) != (beforeId == null)) {
            throw new IllegalArgumentException("历史审批游标时间与标识必须同时提供");
        }
    }
}
