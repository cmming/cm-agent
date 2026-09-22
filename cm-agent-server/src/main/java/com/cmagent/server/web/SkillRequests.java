package com.cmagent.server.web;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** 技能管理接口的请求对象集合。 */
public final class SkillRequests {
    private SkillRequests() {
    }

    /**
     * 显式设置技能启用状态，避免 toggle 在重试时产生相反结果。
     *
     * @param enabled 目标启用状态
     */
    public record EnabledRequest(boolean enabled) {
    }

    /**
     * 条件更新一个技能逻辑依赖到租户工具的映射。
     *
     * <p>请求体不接收 tenantId：租户只能来自认证主体，客户端传入的租户字段一律忽略，
     * 避免通过改包把映射写到别的租户。</p>
     *
     * @param toolId 目标租户工具标识，由服务端在可信租户内重新解析
     * @param expectedRevision 调用方看到的技能级映射修订，用于条件更新
     */
    public record DependencyMappingRequest(
            @NotNull(message = "toolId 不能为空") UUID toolId,
            long expectedRevision
    ) {
    }
}
