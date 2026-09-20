package com.cmagent.core.domain;

import com.cmagent.api.ApiErrorCode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次真实技能读取尝试的持久化记录，与业务工具调用记录相互独立。
 *
 * @param id 读取记录标识
 * @param tenantId 记录所属租户
 * @param runId 所属 Run 标识
 * @param modelCallId 模型侧读取调用标识，用于幂等判断
 * @param attemptNo Run 内累计尝试序号，从 1 开始
 * @param skillId 已解析的技能标识，无法解析时为空
 * @param versionId 已解析的固定版本标识，无法解析时为空
 * @param path 已校验的资源路径，输入无法安全记录时为空
 * @param status 读取结果
 * @param deliveredBytes 实际交给模型的 UTF-8 字节数，未成功时为 0
 * @param durationMillis 受控读取耗时毫秒数
 * @param errorCode 失败或拒绝时的稳定错误码
 * @param errorId 失败或拒绝时用于关联日志的错误编号
 * @param createdAt 记录创建时间
 */
public record SkillLoadRecord(
        UUID id,
        UUID tenantId,
        UUID runId,
        String modelCallId,
        int attemptNo,
        UUID skillId,
        UUID versionId,
        String path,
        SkillLoadStatus status,
        int deliveredBytes,
        long durationMillis,
        ApiErrorCode errorCode,
        String errorId,
        Instant createdAt
) {

    /** 校验成功与失败字段互斥，防止将未交付正文计入成功预算。 */
    public SkillLoadRecord {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        modelCallId = requireText(modelCallId, "modelCallId 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("技能读取尝试序号必须从 1 开始");
        }
        if ((skillId == null) != (versionId == null)) {
            throw new IllegalArgumentException("技能与版本标识必须同时存在或同时为空");
        }
        if (deliveredBytes < 0 || durationMillis < 0) {
            throw new IllegalArgumentException("技能读取字节数和耗时不能为负数");
        }
        if (status == SkillLoadStatus.SUCCEEDED) {
            Objects.requireNonNull(skillId, "成功读取必须记录 skillId");
            Objects.requireNonNull(path, "成功读取必须记录 path");
            if (errorCode != null || (errorId != null && !errorId.isBlank())) {
                throw new IllegalArgumentException("成功读取不能记录错误信息");
            }
        } else {
            if (deliveredBytes != 0) {
                throw new IllegalArgumentException("未成功的技能读取不能记录已交付字节");
            }
            Objects.requireNonNull(errorCode, "失败读取必须记录 errorCode");
            errorId = requireText(errorId, "失败读取必须记录 errorId");
        }
    }

    private static String requireText(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
