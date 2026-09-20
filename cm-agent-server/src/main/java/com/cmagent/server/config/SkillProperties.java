package com.cmagent.server.config;

import com.cmagent.server.service.SkillPackageLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 技能上传和运行预算配置；第一版只允许从经过评审的默认值向下收紧。
 *
 * <p>限制不能由技能包元数据覆盖。若未来需要放大默认值，必须重新评估内存、模型上下文
 * 和拒绝服务风险，而不是通过部署配置静默扩大。</p>
 */
@ConfigurationProperties(prefix = "cm-agent.skills")
public class SkillProperties {

    private boolean enabled;
    private int maxZipBytes = 2 * 1024 * 1024;
    private int maxExpandedBytes = 4 * 1024 * 1024;
    private int maxFiles = 64;
    private int maxInstructionBytes = 32 * 1024;
    private int maxResourceBytes = 64 * 1024;
    private int maxPathLength = 240;
    private int maxBoundSkills = 20;
    private int maxRunBytes = 8 * 1024 * 1024;
    private int maxLoadAttempts = 32;
    private int maxLoadedBytes = 256 * 1024;

    /** @return 是否允许新增、更新、启用和绑定技能 */
    public boolean isEnabled() { return enabled; }
    /** @param enabled 是否启用技能写入能力 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /** @return ZIP 上传字节上限 */
    public int getMaxZipBytes() { return maxZipBytes; }
    /** @param value ZIP 上传字节上限 */
    public void setMaxZipBytes(int value) { this.maxZipBytes = value; }
    /** @return 解压文本总字节上限 */
    public int getMaxExpandedBytes() { return maxExpandedBytes; }
    /** @param value 解压文本总字节上限 */
    public void setMaxExpandedBytes(int value) { this.maxExpandedBytes = value; }
    /** @return 普通文件数量上限 */
    public int getMaxFiles() { return maxFiles; }
    /** @param value 普通文件数量上限 */
    public void setMaxFiles(int value) { this.maxFiles = value; }
    /** @return SKILL.md 字节上限 */
    public int getMaxInstructionBytes() { return maxInstructionBytes; }
    /** @param value SKILL.md 字节上限 */
    public void setMaxInstructionBytes(int value) { this.maxInstructionBytes = value; }
    /** @return 单个资源字节上限 */
    public int getMaxResourceBytes() { return maxResourceBytes; }
    /** @param value 单个资源字节上限 */
    public void setMaxResourceBytes(int value) { this.maxResourceBytes = value; }
    /** @return 相对路径字符上限 */
    public int getMaxPathLength() { return maxPathLength; }
    /** @param value 相对路径字符上限 */
    public void setMaxPathLength(int value) { this.maxPathLength = value; }
    /** @return 单 Agent 绑定技能数量上限 */
    public int getMaxBoundSkills() { return maxBoundSkills; }
    /** @param value 单 Agent 绑定技能数量上限 */
    public void setMaxBoundSkills(int value) { this.maxBoundSkills = value; }
    /** @return 单 Run 准备的技能文本字节上限 */
    public int getMaxRunBytes() { return maxRunBytes; }
    /** @param value 单 Run 准备的技能文本字节上限 */
    public void setMaxRunBytes(int value) { this.maxRunBytes = value; }
    /** @return 单 Run 累计读取尝试次数上限 */
    public int getMaxLoadAttempts() { return maxLoadAttempts; }
    /** @param value 单 Run 累计读取尝试次数上限 */
    public void setMaxLoadAttempts(int value) { this.maxLoadAttempts = value; }
    /** @return 单 Run 累计交付模型原文字节上限 */
    public int getMaxLoadedBytes() { return maxLoadedBytes; }
    /** @param value 单 Run 累计交付模型原文字节上限 */
    public void setMaxLoadedBytes(int value) { this.maxLoadedBytes = value; }

    /**
     * 校验所有限制处于 1 到默认值之间。
     *
     * @throws IllegalStateException 任一限制为零、负数或超过第一版默认值时抛出
     */
    public void validate() {
        check("max-zip-bytes", maxZipBytes, 2 * 1024 * 1024);
        check("max-expanded-bytes", maxExpandedBytes, 4 * 1024 * 1024);
        check("max-files", maxFiles, 64);
        check("max-instruction-bytes", maxInstructionBytes, 32 * 1024);
        check("max-resource-bytes", maxResourceBytes, 64 * 1024);
        check("max-path-length", maxPathLength, 240);
        check("max-bound-skills", maxBoundSkills, 20);
        check("max-run-bytes", maxRunBytes, 8 * 1024 * 1024);
        check("max-load-attempts", maxLoadAttempts, 32);
        check("max-loaded-bytes", maxLoadedBytes, 256 * 1024);
    }

    /** @return 供无落盘解析器使用的限制快照 */
    public SkillPackageLimits toPackageLimits() {
        return new SkillPackageLimits(
                maxZipBytes, maxExpandedBytes, maxFiles,
                maxInstructionBytes, maxResourceBytes, maxPathLength);
    }

    private static void check(String name, int value, int maximum) {
        if (value < 1 || value > maximum) {
            throw new IllegalStateException("cm-agent.skills." + name + " 必须在 1 到 " + maximum + " 之间");
        }
    }
}
