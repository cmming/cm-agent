package com.cmagent.core.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * 技能版本中的一份受控 UTF-8 文本资源。
 *
 * @param tenantId 资源所属租户
 * @param skillId 稳定技能标识
 * @param versionId 资源所属版本标识
 * @param path 技能包内规范化相对路径
 * @param mediaType 受支持的文本媒体类型
 * @param content UTF-8 文本正文
 * @param byteLength 正文 UTF-8 字节数，用于运行预算
 * @param sha256 资源正文的 SHA-256 十六进制摘要
 */
public record SkillResource(
        UUID tenantId,
        UUID skillId,
        UUID versionId,
        String path,
        String mediaType,
        String content,
        int byteLength,
        String sha256
) {

    /** 校验归属键和真实字节数，禁止使用字符数绕过加载预算。 */
    public SkillResource {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(skillId, "skillId 不能为空");
        Objects.requireNonNull(versionId, "versionId 不能为空");
        path = requireText(path, "path 不能为空");
        mediaType = requireText(mediaType, "mediaType 不能为空");
        Objects.requireNonNull(content, "content 不能为空");
        if (byteLength != content.getBytes(StandardCharsets.UTF_8).length) {
            throw new IllegalArgumentException("技能资源字节数与 UTF-8 正文不一致");
        }
        Objects.requireNonNull(sha256, "sha256 不能为空");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 必须是 64 位小写十六进制字符串");
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
