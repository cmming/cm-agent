package com.cmagent.core.domain;

import java.text.Normalizer;
import java.util.*;

/**
 * 经服务端验证的不可变 manifest 文件项；展示路径不参与磁盘寻址。
 * @param id 服务端生成的文件标识
 * @param relativePath 去除单个选择根后的相对文件路径
 * @param sizeBytes 声明的 UTF-8 明文字节，存储时仍须实际复核
 * @param sha256 声明的 SHA-256 小写十六进制摘要，存储时仍须重算
 */
public record WorkspaceImportFile(UUID id, String relativePath, int sizeBytes, String sha256) {
    /** 只读资料的固定文本类型；部署配置只能缩小范围。 */
    public static final Set<String> TYPES = Set.of(".md", ".txt", ".json", ".csv");
    /** 显式拒绝的私有/生成目录，避免浏览器预检被绕过。 */
    private static final Set<String> EXCLUDED = Set.of(".git", ".ssh", ".aws", ".codex", ".workbuddy", "node_modules");

    public WorkspaceImportFile {
        Objects.requireNonNull(id);
        if (!safePath(relativePath) || sizeBytes < 1 || sizeBytes > 32768
                || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("导入文件路径、长度或摘要不合法");
    }

    /** @return 路径的比较身份，仅用于检测 Unicode 与大小写碰撞，不能用于修复非法路径 */
    public String pathIdentity() { return Normalizer.normalize(relativePath, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }

    /**
     * 拒绝宿主路径、别名及敏感名称；名称检查不能保证正文没有秘密，仍需用户核对。
     * @param path 不可信相对路径
     * @return 是否为可接受的单根相对文本路径
     */
    public static boolean safePath(String path) {
        if (path == null || path.isBlank() || path.length() > 160 || path.startsWith("/")
                || path.contains("\\") || path.contains(":") || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(path)
                || path.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT)) return false;
        // 不对客户端路径做消除 ../ 的归一化；兼容字符形成的别名直接拒绝。
        if (!Normalizer.normalize(path, Normalizer.Form.NFKC).equals(path)) return false;
        for (String part : path.split("/", -1)) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || EXCLUDED.contains(lower) || lower.startsWith(".env") || lower.contains("credential")
                    || lower.contains("secret") || lower.contains("password") || lower.contains("private_key") || lower.contains("private-key")
                    || lower.contains("api_key") || lower.contains("access_token") || lower.startsWith("id_rsa") || lower.startsWith("id_ed25519") || lower.startsWith("id_ecdsa")
                    || lower.matches("(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) return false;
        }
        return TYPES.stream().anyMatch(path::endsWith);
    }

    /** 防止异常诊断中默认 record 文本意外带出用户文件名。 */
    @Override public String toString() { return "WorkspaceImportFile[id=" + id + ", sizeBytes=" + sizeBytes + "]"; }
}
