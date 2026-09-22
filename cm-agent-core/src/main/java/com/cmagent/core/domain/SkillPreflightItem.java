package com.cmagent.core.domain;

import com.cmagent.api.ApiErrorCode;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次预检中针对逻辑依赖和可选 Agent 的明细结果。
 *
 * @param checkId 所属预检标识
 * @param agentId 受影响 Agent；纯结构检查可为空
 * @param logicalKey 技能版本声明的逻辑工具键
 * @param required 是否为必需依赖
 * @param toolId 已解析的租户工具；未映射时为空
 * @param status 明细检查状态
 * @param errorCode 对外稳定错误码；就绪项可为空
 * @param message 已脱敏的中文结果说明
 * @param errorId 需要后台定位时使用的关联编号，否则为空
 */
public record SkillPreflightItem(
        UUID checkId,
        UUID agentId,
        String logicalKey,
        boolean required,
        UUID toolId,
        SkillPreflightItemStatus status,
        ApiErrorCode errorCode,
        String message,
        String errorId
) {
    /** 校验明细归属和可展示结果，不允许失败项缺少稳定错误码。 */
    public SkillPreflightItem {
        Objects.requireNonNull(checkId, "checkId 不能为空");
        logicalKey = SkillDependencyResolution.requireLogicalKey(logicalKey);
        Objects.requireNonNull(status, "status 不能为空");
        message = message == null ? "" : message.strip();
        errorId = errorId == null || errorId.isBlank() ? null : errorId.strip();
        if (status == SkillPreflightItemStatus.READY && errorCode != null) {
            throw new IllegalArgumentException("就绪依赖不能携带错误码");
        }
        if (status != SkillPreflightItemStatus.READY && errorCode == null) {
            throw new NullPointerException("未就绪依赖必须携带错误码");
        }
    }
}
