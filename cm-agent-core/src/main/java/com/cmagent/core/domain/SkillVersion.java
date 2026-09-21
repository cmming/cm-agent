package com.cmagent.core.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 技能的一份不可变版本，保存指令正文、描述及只读元数据。
 *
 * @param id 版本标识
 * @param tenantId 版本所属租户
 * @param skillId 稳定技能标识
 * @param versionNo 从 1 开始的租户技能版本号
 * @param description 供模型选择技能时使用的简短描述
 * @param metadata 解析后的文本元数据，只接受 JSON 等价值并递归冻结
 * @param content {@code SKILL.md} 的指令正文
 * @param sha256 版本内容的 SHA-256 十六进制摘要
 * @param createdBy 创建版本的主体标识
 * @param createdAt 版本创建时间
 */
public record SkillVersion(
        UUID id,
        UUID tenantId,
        UUID skillId,
        int versionNo,
        String description,
        Map<String, Object> metadata,
        String content,
        String sha256,
        String createdBy,
        Instant createdAt
) {

    /** 冻结元数据的全部嵌套层级，防止运行期间被调用方修改。 */
    public SkillVersion {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(skillId, "skillId 不能为空");
        if (versionNo < 1) {
            throw new IllegalArgumentException("技能版本号必须从 1 开始");
        }
        description = requireText(description, "description 不能为空");
        metadata = freezeMap(Objects.requireNonNull(metadata, "metadata 不能为空"));
        Objects.requireNonNull(content, "content 不能为空");
        sha256 = requireSha256(sha256);
        createdBy = requireText(createdBy, "createdBy 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }

    private static Map<String, Object> freezeMap(Map<?, ?> source) {
        Map<String, Object> frozen = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException("技能元数据键必须是非空字符串");
            }
            frozen.put(key, freezeValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    private static Object freezeValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return freezeMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> frozen = new ArrayList<>(list.size());
            list.forEach(item -> frozen.add(freezeValue(item)));
            return Collections.unmodifiableList(frozen);
        }
        throw new IllegalArgumentException("技能元数据只支持文本、数字、布尔、空值、列表和对象");
    }

    private static String requireSha256(String value) {
        Objects.requireNonNull(value, "sha256 不能为空");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 必须是 64 位小写十六进制字符串");
        }
        return value;
    }

    private static String requireText(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
