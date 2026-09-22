package com.cmagent.server.service;

import com.cmagent.core.domain.SkillPreflightCheck;
import com.cmagent.core.domain.SkillPreflightItem;
import com.cmagent.core.domain.SkillPreflightStatus;

import java.util.List;
import java.util.Objects;

/**
 * 一次已完成预检的汇总与明细快照。
 *
 * @param check 已持久化的预检汇总
 * @param items 明细结果，按依赖声明顺序和受检 Agent 排列
 */
public record SkillPreflightResult(SkillPreflightCheck check, List<SkillPreflightItem> items) {
    /** 冻结明细并校验汇总归属。 */
    public SkillPreflightResult {
        Objects.requireNonNull(check, "check 不能为空");
        items = List.copyOf(items == null ? List.of() : items);
        for (SkillPreflightItem item : items) {
            if (!item.checkId().equals(check.id())) {
                throw new IllegalArgumentException("预检明细必须属于同一预检标识");
            }
        }
    }

    /** @return 汇总是否允许继续依赖该版本的治理动作 */
    public boolean passing() {
        return check.status() != SkillPreflightStatus.FAILED;
    }
}
