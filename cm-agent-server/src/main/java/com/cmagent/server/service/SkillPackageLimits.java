package com.cmagent.server.service;

/**
 * 技能 ZIP 解析阶段使用的资源上限。
 *
 * @param zipBytes ZIP 上传字节上限
 * @param expandedBytes 全部普通文件实际解压字节上限
 * @param fileCount 普通文件数量上限
 * @param instructionBytes {@code SKILL.md} 字节上限
 * @param resourceBytes 单个参考资源字节上限
 * @param pathLength 规范化相对路径字符上限
 */
public record SkillPackageLimits(
        int zipBytes,
        int expandedBytes,
        int fileCount,
        int instructionBytes,
        int resourceBytes,
        int pathLength
) {

    /** 第一版经过评审的默认上限，只允许配置层向下收紧。 */
    public static SkillPackageLimits defaults() {
        return new SkillPackageLimits(
                2 * 1024 * 1024, 4 * 1024 * 1024, 64,
                32 * 1024, 64 * 1024, 240);
    }

    /** 校验每项限制必须为正数。 */
    public SkillPackageLimits {
        if (zipBytes < 1 || expandedBytes < 1 || fileCount < 1
                || instructionBytes < 1 || resourceBytes < 1 || pathLength < 1) {
            throw new IllegalArgumentException("技能包限制必须为正数");
        }
    }
}
