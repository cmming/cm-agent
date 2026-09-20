package com.cmagent.core.runtime;

import com.cmagent.api.PrincipalRef;

import java.util.Objects;
import java.util.UUID;

/**
 * 封装一次受治理技能读取的可信运行上下文和已解析资源定位。
 *
 * @param principal 发起原 Run 的可信认证主体
 * @param agentId 执行 Run 的 Agent 标识
 * @param runId 所属 Run 标识
 * @param modelCallId 模型读取调用标识
 * @param attemptId 本次尝试的错误关联标识
 * @param skillId 已解析的技能标识，无法解析时为空
 * @param versionId 已解析的固定版本标识，无法解析时为空
 * @param path 模型请求的受校验资源路径，无法安全保留时为空
 */
public record SkillReadRequest(
        PrincipalRef principal,
        UUID agentId,
        UUID runId,
        String modelCallId,
        UUID attemptId,
        UUID skillId,
        UUID versionId,
        String path
) {

    /** 校验可信运行字段，并保持无法解析的技能和版本同时为空。 */
    public SkillReadRequest {
        Objects.requireNonNull(principal, "principal 不能为空");
        Objects.requireNonNull(agentId, "agentId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(modelCallId, "modelCallId 不能为空");
        if (modelCallId.isBlank()) {
            throw new IllegalArgumentException("modelCallId 不能为空");
        }
        Objects.requireNonNull(attemptId, "attemptId 不能为空");
        if ((skillId == null) != (versionId == null)) {
            throw new IllegalArgumentException("技能与版本标识必须同时存在或同时为空");
        }
        if (path != null && path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空白");
        }
    }
}
