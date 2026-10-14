package com.cmagent.server.config;

import com.cmagent.core.domain.WorkspaceContextResource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 管理员部署的只读资料映射；默认关闭，不接受 HTTP 或模型覆盖。 */
@ConfigurationProperties("cm-agent.workspace-context")
public class WorkspaceContextProperties {
    /** 是否显式启用参考资料，关闭时旧运行不增加输入或状态。 */
    private boolean enabled;
    /** 部署者私有资料根，禁止位于应用仓库、用户主目录或其祖先中。 */
    private String rootDirectory = "";
    /** 唯一 tenant/Agent 映射，资料文件必须逐项声明，不自动扫描目录。 */
    private List<Binding> bindings = List.of();
    /** 单运行资料数量，默认4，硬上限8。 */
    private int maxFiles = 4;
    /** 单文件 UTF-8 字节，默认16KiB，硬上限32KiB。 */
    private int maxFileBytes = 16384;
    /** 单运行累计字节，默认32KiB，硬上限64KiB。 */
    private int maxTotalBytes = 32768;
    /** 本地文件读取预算，默认2秒，禁止网络文件系统，硬上限5秒。 */
    private Duration readTimeout = Duration.ofSeconds(2);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getRootDirectory() { return rootDirectory; }
    public void setRootDirectory(String value) { rootDirectory = value; }
    public List<Binding> getBindings() { return bindings; }
    public void setBindings(List<Binding> value) { bindings = value == null ? List.of() : List.copyOf(value); }
    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int value) { maxFiles = value; }
    public int getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(int value) { maxFileBytes = value; }
    public int getMaxTotalBytes() { return maxTotalBytes; }
    public void setMaxTotalBytes(int value) { maxTotalBytes = value; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = value; }

    /** 配置不合法时拒绝启动；错误不包含根路径或资料正文。 */
    public void validate() {
        if (maxFiles < 1 || maxFiles > 8 || maxFileBytes < 1 || maxFileBytes > 32768 || maxTotalBytes < maxFileBytes
                || maxTotalBytes > 65536 || readTimeout == null || readTimeout.toMillis() < 10 || readTimeout.toMillis() > 5000
                || enabled && (rootDirectory == null || rootDirectory.isBlank())) throw new IllegalStateException("只读工作区预算或私有根未正确配置");
        var identities = new java.util.HashSet<String>();
        for (var binding : bindings) {
            if (binding == null || binding.tenantId() == null || binding.agentId() == null || !safeRelative(binding.directory())
                    || binding.files().isEmpty() || binding.files().size() > maxFiles
                    || binding.files().stream().anyMatch(path -> !WorkspaceContextResource.safePath(path))
                    || binding.files().stream().distinct().count() != binding.files().size()
                    || !identities.add(binding.tenantId() + ":" + binding.agentId())) throw new IllegalStateException("只读工作区映射不合法或重复");
        }
    }

    /** 相对目录只能逐层定位，禁止路径别名、控制字符、盘符及上级目录。 */
    public static boolean safeRelative(String path) {
        return path != null && !path.isBlank() && path.length() <= 160 && !path.startsWith("/") && !path.contains("\\")
                && !path.contains(":") && path.chars().noneMatch(Character::isISOControl)
                && java.util.Arrays.stream(path.split("/", -1)).noneMatch(p -> p.isBlank() || p.equals(".") || p.equals(".."));
    }

    /**
     * 管理员声明的可信映射，不从运行请求中解析。
     * @param tenantId 映射的认证租户
     * @param agentId 映射的服务端 Agent
     * @param directory 私有根中的固定相对目录
     * @param files 明确允许读取的相对文本路径，不遍历未声明资料
     */
    public record Binding(UUID tenantId, UUID agentId, String directory, List<String> files) {
        public Binding { files = List.copyOf(files); }
    }
}
