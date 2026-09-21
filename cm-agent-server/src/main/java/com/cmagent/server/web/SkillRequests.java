package com.cmagent.server.web;

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
}
