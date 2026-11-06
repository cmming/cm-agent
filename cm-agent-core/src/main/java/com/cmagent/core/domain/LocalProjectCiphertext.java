package com.cmagent.core.domain;

import java.util.Arrays;
import java.util.Base64;

/**
 * 本地项目受保护字段的编码边界，防止将普通路径/补丁误写入密文字段。
 *
 * <p>匹配当前宿主v1 AES/GCM信封（12字节IV和至少16字节认证标签）。
 * 这里只检查有界格式，不持有密钥或伪称完成密码学认证；解密方还须核验tenant/owner/根或清单上下文。</p>
 */
final class LocalProjectCiphertext {
    private LocalProjectCiphertext() {}

    /**
     * @param value 宿主已加密的版本化信封
     * @param maxCharacters 字符数硬界，先检查再进行Base64分配
     */
    static void require(String value, int maxCharacters) {
        if (value == null || value.length() > maxCharacters) throw invalid();
        String[] parts = value.split(":", -1);
        if (parts.length != 3 || !parts[0].equals("v1")) throw invalid();
        byte[] iv = null, payload = null;
        try {
            iv = Base64.getDecoder().decode(parts[1]);
            payload = Base64.getDecoder().decode(parts[2]);
            if (iv.length != 12 || payload.length <= 16) throw invalid();
        } catch (IllegalArgumentException failure) {
            throw invalid();
        } finally {
            if (iv != null) Arrays.fill(iv, (byte) 0);
            if (payload != null) Arrays.fill(payload, (byte) 0);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("项目受保护字段必须为有效的有界密文信封");
    }
}

