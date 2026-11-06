package com.cmagent.core.runtime;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 本地项目相对路径和默认可读文件策略，不执行文件I/O或授予项目权限。
 *
 * <p>拒绝有歧义的输入，而不是清洗成另一条可访问路径；实际根身份、链接/文件句柄、
 * owner及读取预算仍须由宿主逐次核验。脚本文本可读不代表允许执行。</p>
 */
public final class LocalProjectPathPolicy {
    /** 私有凭据和运行目录不能因选中父项目就成为模型资料。 */
    private static final Set<String> PRIVATE_NAMES = Set.of(".git", ".ssh", ".aws", ".azure", ".gnupg",
            ".codex", ".workbuddy", ".cm-agent", ".m2", ".npm", ".npmrc", ".kube", ".docker",
            "credentials", "credentials.json", "secrets.json", "secrets.yml", "secrets.yaml",
            "id_rsa", "id_ed25519", "id_ecdsa");
    /** 默认不扫描生成/依赖/IDE状态，避免无界目录和非项目运行数据。 */
    private static final Set<String> GENERATED_NAMES = Set.of("node_modules", "target", "build", "dist", ".gradle", ".idea");
    /** 明确的密钥容器扩展名，不将其交给文本读取器或搜索器。 */
    private static final Set<String> KEY_TYPES = Set.of(".pem", ".key", ".p12", ".pfx", ".jks", ".keystore");
    /** 项目常见文本，执行脚本仍必须走另外的HIGH权限链。 */
    private static final Set<String> TEXT_TYPES = Set.of(".java", ".xml", ".yml", ".yaml", ".properties", ".md", ".txt",
            ".json", ".csv", ".js", ".ts", ".tsx", ".jsx", ".html", ".css", ".sql", ".py", ".sh", ".ps1",
            ".cjs", ".mjs", ".gradle", ".kts", ".toml");
    /** 没有扩展名但常见的项目文本，不把任意二进制无后缀文件当作可读文本。 */
    private static final Set<String> TEXT_NAMES = Set.of("dockerfile", "makefile", ".gitignore", ".dockerignore", ".editorconfig");
    /** Windows设备名在其他OS同样拒绝，防止已登记的请求迁移到Windows后语义改变。 */
    private static final Pattern DEVICE = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]+|lpt[0-9]+|conin\\$|conout\\$|clock\\$)(\\..*)?");
    /** 控制/格式字符包括不可见方向和零宽标记，不能用于混淆审批所见的路径。 */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    /** 跨平台统一拒绝Windows非法名称字符，不把通配语法当成真实文件路径。 */
    private static final Pattern RESERVED = Pattern.compile("[<>\"|?*]");

    private LocalProjectPathPolicy() {}

    /**
     * @param name 单个真实目录/文件名，不是可用于寻址的路径
     * @return 是否为默认私有来源；根本身及祖先也须检查，不能把.ssh选成根后绕过相对路径策略
     */
    public static boolean privateComponent(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return PRIVATE_NAMES.contains(lower) || lower.startsWith(".env");
    }

    /**
     * 校验相对路径而不改变任何字符。
     * @param value 只使用正斜杠的根内相对路径，最大1024字符和32层
     * @param allowRoot 是否允许空字符串表示已授权项目根；文件读取/修改必须为false
     * @return 原路径，不能把返回值当成已完成文件系统授权
     * @throws IllegalArgumentException 非法、绝对、越界或有跨平台歧义的路径
     */
    public static String requireRelative(String value, boolean allowRoot) {
        if (value == null) throw invalid();
        if (value.isEmpty()) {
            if (allowRoot) return value;
            throw invalid();
        }
        if (value.length() > 1024 || value.startsWith("/") || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0
                || !Normalizer.normalize(value, Normalizer.Form.NFKC).equals(value) || INVISIBLE.matcher(value).find()
                || RESERVED.matcher(value).find()) throw invalid();
        String[] segments = value.split("/", -1);
        if (segments.length > 32) throw invalid();
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..") || segment.length() > 255
                    || segment.endsWith(".") || segment.endsWith(" ") || DEVICE.matcher(segment).matches()) throw invalid();
        }
        return value;
    }

    /**
     * @param relative 已校验的文件或目录相对路径
     * @return 路径任一部分属于默认私有/生成来源时true，不探测文件是否存在
     */
    public static boolean excluded(String relative) {
        requireRelative(relative, false);
        for (String segment : relative.split("/")) {
            String lower = segment.toLowerCase(Locale.ROOT);
            if (privateComponent(segment) || GENERATED_NAMES.contains(lower)) return true;
        }
        String lower = relative.toLowerCase(Locale.ROOT);
        return KEY_TYPES.stream().anyMatch(lower::endsWith);
    }

    /**
     * @param relative 受控文件的相对路径
     * @return 类型与名称允许作为文本候选；真实编码、内容安全和字节预算仍须读取器核验
     */
    public static boolean textCandidate(String relative) {
        requireRelative(relative, false);
        if (excluded(relative)) return false;
        String name = relative.substring(relative.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (TEXT_NAMES.contains(name)) return true;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_TYPES.contains(name.substring(dot));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("项目路径必须是无歧义的根内相对路径");
    }
}

