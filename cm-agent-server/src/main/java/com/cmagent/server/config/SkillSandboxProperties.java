package com.cmagent.server.config;

import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/** 仅由部署者控制的沙箱策略，技能元数据和模型参数不能覆盖安全边界。 */
public class SkillSandboxProperties {
    /** 独立文件收集与存储策略；默认关闭，不改变原文本执行行为。 */
    @NestedConfigurationProperty
    private final SkillArtifactProperties artifacts = new SkillArtifactProperties();
    /** @return 文件产物部署策略 */
    public SkillArtifactProperties getArtifacts() { return artifacts; }
    /** 只由部署注册的后端标识，不能指定实现类。 */
    private String backend = "docker";
    /** 远程 host:port 的精确白名单，空集合拒绝远程端点。 */
    private java.util.List<String> allowedTargets = java.util.List.of();
    /** 独立沙箱凭据主密钥，默认不提供任何可用密钥。 */
    private String encryptionKey = "";
    /** 部署默认连接；空 mode 保留旧 DOCKER_* 环境行为，显式配置后不继承。嵌套注解仅用于展开连接配置元数据。 */
    @NestedConfigurationProperty
    private final DockerConnectionProperties connection = new DockerConnectionProperties();
    /** @return 默认后端标识 */
    public String getBackend() { return backend; }
    /** @param value 默认后端标识 */
    public void setBackend(String value) { backend = value; }
    /** @return 部署允许目标 */
    public java.util.List<String> getAllowedTargets() { return allowedTargets; }
    /** @param value 精确 host:port 清单 */
    public void setAllowedTargets(java.util.List<String> value) { allowedTargets = java.util.List.copyOf(value); }
    /** @return 仅供加密组件使用的主密钥，禁止诊断输出 */
    public String getEncryptionKey() { return encryptionKey; }
    /** @param value 外部注入的 Base64 主密钥 */
    public void setEncryptionKey(String value) { encryptionKey = value; }
    /** @return 部署默认连接 */
    public DockerConnectionProperties getConnection() { return connection; }
    /** 默认关闭，升级不会自动允许执行导入代码。 */
    private boolean enabled;
    /** 部署者预拉取的可信 Python 镜像；生产应使用 digest 固定内容。 */
    private String image = "python:3.12-alpine";
    /** 可选的强化 OCI runtime；配置后不可用时拒绝启动，不回退默认 runtime。 */
    private String runtime = "";
    /** 整次容器启动、输入与执行的时间预算，应小于 AgentScope 工具超时。 */
    private Duration timeout = Duration.ofSeconds(15);
    /** 单次 stdout 与 stderr 合计原始字节上限。 */
    private int maxOutputBytes = 32 * 1024;
    /** 模型提供给脚本的 stdin 字节上限。 */
    private int maxInputBytes = 32 * 1024;
    /** 单实例最多同时持有的执行容器数量。 */
    private int maxConcurrent = 2;

    /** @return 是否开放执行 */
    public boolean isEnabled() { return enabled; }
    /** @param value 是否开放执行 */
    public void setEnabled(boolean value) { enabled = value; }
    /** @return 可信镜像引用 */
    public String getImage() { return image; }
    /** @param value 由部署环境指定的镜像引用 */
    public void setImage(String value) { image = value; }
    /** @return 可选 OCI runtime 名称 */
    public String getRuntime() { return runtime; }
    /** @param value 可选 OCI runtime 名称 */
    public void setRuntime(String value) { runtime = value; }
    /** @return 单次执行时间预算 */
    public Duration getTimeout() { return timeout; }
    /** @param value 1 至 20 秒的执行预算 */
    public void setTimeout(Duration value) { timeout = value; }
    /** @return 原始输出字节上限 */
    public int getMaxOutputBytes() { return maxOutputBytes; }
    /** @param value 原始输出字节上限 */
    public void setMaxOutputBytes(int value) { maxOutputBytes = value; }
    /** @return stdin 字节上限 */
    public int getMaxInputBytes() { return maxInputBytes; }
    /** @param value stdin 字节上限 */
    public void setMaxInputBytes(int value) { maxInputBytes = value; }
    /** @return 单实例并发上限 */
    public int getMaxConcurrent() { return maxConcurrent; }
    /** @param value 单实例并发上限 */
    public void setMaxConcurrent(int value) { maxConcurrent = value; }

    /** 启动期校验；配置只能收紧首版限额，不能静默扩大攻击面。 */
    public void validate() {
        artifacts.validate();
        if (image == null || !image.matches("[a-zA-Z0-9][a-zA-Z0-9./_:@-]{0,255}")
                || runtime == null || !runtime.matches("[a-zA-Z0-9_.-]{0,64}")) {
            throw new IllegalStateException("技能沙箱镜像或 runtime 配置不合法");
        }
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
                || timeout.compareTo(Duration.ofSeconds(20)) > 0
                || maxOutputBytes < 1 || maxOutputBytes > 32 * 1024
                || maxInputBytes < 1 || maxInputBytes > 32 * 1024
                || maxConcurrent < 1 || maxConcurrent > 2) {
            throw new IllegalStateException("技能沙箱时间、输入输出或并发限额不合法");
        }
    }
}
