package com.cmagent.server.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 完成全部安全校验后形成的单技能文本包。
 *
 * @param name 租户内稳定技能名称
 * @param description 供模型选择技能的适用情境描述
 * @param metadata 安全 YAML 解析得到的附加元数据
 * @param content 非空指令正文
 * @param dependencies 按声明顺序冻结的逻辑工具依赖
 * @param resources 以技能根为基准的文本资源路径和正文
 * @param sha256 与 ZIP 元信息无关的规范内容摘要
 */
public record ParsedSkillPackage(
        String name,
        String description,
        Map<String, Object> metadata,
        String content,
        List<ParsedSkillDependency> dependencies,
        Map<String, String> resources,
        String sha256
) {

    /** 冻结解析结果，使保存前的权限和事务阶段无法被调用方篡改。 */
    public ParsedSkillPackage {
        Objects.requireNonNull(name, "name 不能为空");
        Objects.requireNonNull(description, "description 不能为空");
        Objects.requireNonNull(content, "content 不能为空");
        Objects.requireNonNull(sha256, "sha256 不能为空");
        metadata = freezeMap(Objects.requireNonNull(metadata, "metadata 不能为空"));
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies 不能为空"));
        resources = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(resources, "resources 不能为空")));
    }

    private static Map<String, Object> freezeMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("技能元数据键必须为字符串");
            }
            result.put(key, freeze(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object freeze(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return freezeMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(freeze(item)));
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException("技能元数据包含不支持的值");
    }
}

/**
 * 完成安全校验后的单个逻辑工具依赖。
 *
 * @param logicalKey 技能包内稳定的逻辑工具键
 * @param required 是否为阻断运行和发布的必需依赖
 * @param description 面向管理员的依赖用途说明
 * @param position 技能包中的声明顺序
 */
record ParsedSkillDependency(String logicalKey, boolean required, String description, int position) {
    ParsedSkillDependency {
        Objects.requireNonNull(logicalKey, "logicalKey 不能为空");
        Objects.requireNonNull(description, "description 不能为空");
        if (position < 0) {
            throw new IllegalArgumentException("position 不能为负数");
        }
    }
}
