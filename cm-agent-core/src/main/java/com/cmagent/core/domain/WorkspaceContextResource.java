package com.cmagent.core.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 已由宿主读取并固定的 UTF-8 参考资料；不包含宿主路径或任何执行权限。
 * @param path 绑定目录内的相对资料标识，仅用于引用
 * @param content 固定正文，仅作为模型参考数据，不得记录到日志
 * @param sizeBytes 正文实际 UTF-8 字节数
 * @param sha256 正文摘要，构造时核对以避免状态恢复被截断或错配
 */
public record WorkspaceContextResource(String path, String content, int sizeBytes, String sha256) {
    public WorkspaceContextResource {
        if (!safePath(path) || content == null || !StandardCharsets.UTF_8.newEncoder().canEncode(content)
                || content.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')
                || sizeBytes < 1 || sizeBytes > 32768
                || sizeBytes != content.getBytes(StandardCharsets.UTF_8).length || !digest(content).equals(sha256)) {
            throw new IllegalArgumentException("工作区参考资料格式或摘要不合法");
        }
    }

    /** record默认文本会包含正文；诊断文本只能保留引用和统计，JSON模型输入仍显式读取content。 */
    @Override public String toString() { return "WorkspaceContextResource[path=" + path + ", sizeBytes=" + sizeBytes + ", sha256=" + sha256 + "]"; }

    /** 只接受明确的包内文本路径，不接受目录、路径别名、控制字符或宿主绝对路径。 */
    public static boolean safePath(String path) {
        return path != null && !path.isBlank() && path.length() <= 160 && !path.startsWith("/")
                && !path.contains("\\") && !path.contains(":") && path.chars().noneMatch(Character::isISOControl)
                && java.util.Arrays.stream(path.split("/", -1)).noneMatch(p -> p.isBlank() || p.equals(".") || p.equals(".."))
                && java.util.List.of(".md", ".txt", ".json", ".csv").stream().anyMatch(path::endsWith);
    }

    /** @return UTF-8 文本的稳定摘要；不返回正文或主机信息 */
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("工作区摘要算法不可用", failure); }
    }
}
