package com.cmagent.server.config;

import com.cmagent.core.domain.WorkspaceContextResource;
import lombok.Setter;
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
    /** 可供用户选择的登记目录；仅部署者设置，列表不扫描磁盘。 */
    private List<Entry> catalog = List.of();
    /** 单运行资料数量，默认4，硬上限8。 */
    private int maxFiles = 4;
    /** 单文件 UTF-8 字节，默认16KiB，硬上限32KiB。 */
    private int maxFileBytes = 16384;
    /** 单运行累计字节，默认32KiB，硬上限64KiB。 */
    private int maxTotalBytes = 32768;
    /** 本地文件读取预算，默认2秒，禁止网络文件系统，硬上限5秒。 */
    private Duration readTimeout = Duration.ofSeconds(2);
    /** 本机资料导入的独立开关、持久卷与配额；不复用服务器登记根。 */
    private final WorkspaceImportProperties imports = new WorkspaceImportProperties();

    /** @return 嵌套部署策略；不能由 HTTP 或模型修改 */
    public WorkspaceImportProperties getImports() { return imports; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getRootDirectory() { return rootDirectory; }
    public void setRootDirectory(String value) { rootDirectory = value; }
    public List<Binding> getBindings() { return bindings; }
    public void setBindings(List<Binding> value) { bindings = value == null ? List.of() : List.copyOf(value); }
    public List<Entry> getCatalog() { return catalog; }
    public void setCatalog(List<Entry> value) { catalog = value == null ? List.of() : List.copyOf(value); }
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
        imports.validate(this);
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
        if (catalog.size() > 128) throw new IllegalStateException("工作区登记目录数量超过上限");
        var ids = new java.util.HashSet<UUID>();
        var paths = new java.util.ArrayList<Binding>(bindings);
        for (var entry : catalog) {
            if (entry == null || entry.id() == null || !ids.add(entry.id()) || entry.tenantId() == null
                    || entry.name() == null || entry.name().isBlank() || entry.name().length() > 120
                    || entry.name().chars().anyMatch(Character::isISOControl) || entry.agentIds().isEmpty()
                    || entry.agentIds().stream().distinct().count() != entry.agentIds().size()
                    || !safeRelative(entry.directory()) || entry.files().isEmpty() || entry.files().size() > maxFiles
                    || entry.files().stream().anyMatch(path -> !WorkspaceContextResource.safePath(path))
                    || entry.files().stream().distinct().count() != entry.files().size())
                throw new IllegalStateException("工作区登记目录不合法或重复");
            // 相对路径的大小写、目录联接和实际别名不能绕过重叠检查；部署目录不存在时仍检查词法边界。
            paths.add(new Binding(entry.tenantId(), entry.agentIds().getFirst(), entry.directory(), entry.files()));
        }
        for (int i = 0; i < paths.size(); i++) for (int j = 0; j < i; j++) {
            var left = paths.get(i); var right = paths.get(j);
            String a = canonical(left.directory()), b = canonical(right.directory());
            boolean overlap = a.equals(b) || a.startsWith(b + "/") || b.startsWith(a + "/");
            // 同租户旧映射可继续共享完全相同目录；登记项允许显式复用旧映射，但不能增加跨租户暴露。
            boolean legacyShare = a.equals(b) && left.tenantId().equals(right.tenantId())
                    && (i < bindings.size() || j < bindings.size());
            if (overlap && !legacyShare) throw new IllegalStateException("工作区目录重复或存在父子范围重叠");
        }
    }

    private String canonical(String directory) {
        var path = java.nio.file.Path.of(rootDirectory).toAbsolutePath().normalize().resolve(directory).normalize();
        try {
            if (java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !path.toRealPath().equals(path)) throw new IllegalStateException("工作区目录不允许路径别名");
        } catch (java.io.IOException failure) { throw new IllegalStateException("工作区目录无法校验"); }
        String value = path.toString().replace('\\', '/');
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? value.toLowerCase(java.util.Locale.ROOT) : value;
    }

    /**
     * 受信部署目录，不接收客户端绝对路径；名称和根内位置仅向已授权主体展示。
     * @param id 不复用的稳定逻辑目录标识
     * @param name 中文展示名称
     * @param tenantId 唯一归属租户
     * @param agentIds 允许选择此目录的 Agent 集合
     * @param directory 私有根内相对目录
     * @param files 明确登记的只读文本文件
     * @param enabled 是否允许新运行与原运行恢复
     */
    public record Entry(UUID id, String name, UUID tenantId, List<UUID> agentIds, String directory, List<String> files, boolean enabled) {
        public Entry { agentIds = List.copyOf(agentIds); files = List.copyOf(files); }
        /** @return 交给原有有界读取器的可信映射，不放宽原路径策略 */
        public Binding binding(UUID agentId) { return new Binding(tenantId, agentId, directory, files); }
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
