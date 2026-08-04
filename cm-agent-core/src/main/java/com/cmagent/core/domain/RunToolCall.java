package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 记录一次运行内工具调用的授权、状态、摘要、耗时和错误信息。
 */
public record RunToolCall(
        UUID id,
        UUID tenantId,
        UUID runId,
        UUID toolId,
        String toolName,
        String inputSummary,
        String outputSummary,
        RunStatus status,
        boolean authorized,
        Long durationMillis,
        String errorMessage,
        Instant createdAt
) {
    /**
     * 校验并规范化运行内工具调用的状态、摘要和耗时。
      *
      * @param id 目标资源标识
      * @param tenantId 当前租户标识
      * @param runId 目标运行标识
      * @param toolId 目标工具标识
      * @param toolName 模型请求调用的工具名称
      * @param inputSummary 已脱敏的工具输入摘要
      * @param outputSummary 已脱敏的工具输出摘要
      * @param status 目标运行状态
      * @param authorized 本次工具调用是否通过授权
      * @param durationMillis 工具调用耗时毫秒数
      * @param errorMessage 已控制敏感信息的错误说明
      * @param createdAt 记录创建时间
     */
    public RunToolCall {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(toolId, "toolId 不能为空");
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        if (durationMillis != null && durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis 不能小于 0");
        }
        inputSummary = normalizeText(inputSummary);
        outputSummary = normalizeText(outputSummary);
        errorMessage = normalizeText(errorMessage);
    }

    /**
     * 将可选文本统一规范为空字符串或原值，避免领域对象保存 {@code null}。
      *
     * @param value 待规范化的文本
     * @return 空白输入对应空字符串，否则返回原值
     */
    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
