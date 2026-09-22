package com.cmagent.core.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Run 创建时固定的逻辑依赖到租户工具映射。
 *
 * @param logicalKey 技能版本声明的逻辑工具键
 * @param toolId 服务端解析出的租户工具标识
 * @param required 该版本中是否将依赖声明为必需
 */
public record SkillDependencyResolution(String logicalKey, UUID toolId, boolean required) {
    /** 校验逻辑键和已解析工具标识，防止运行时再读取可变映射。 */
    public SkillDependencyResolution {
        logicalKey = requireLogicalKey(logicalKey);
        Objects.requireNonNull(toolId, "toolId 不能为空");
    }

    static String requireLogicalKey(String value) {
        Objects.requireNonNull(value, "logicalKey 不能为空");
        String normalized = value.strip();
        if (!normalized.matches("(?:[a-z0-9](?:[a-z0-9]|-(?!-)){0,62}[a-z0-9]|[a-z0-9])")) {
            throw new IllegalArgumentException("logicalKey 必须是 1 到 64 位小写字母、数字或单连字符");
        }
        return normalized;
    }
}
