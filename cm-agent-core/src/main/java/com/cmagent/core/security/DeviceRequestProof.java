package com.cmagent.core.security;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 设备对一条服务端挑战及具体请求签名时使用的不可变内容。
 *
 * <p>服务端必须从自己的挑战仓储、认证设备记录和收到的实际请求重建此对象，不能相信客户端
 * 提供的租户、主体或正文摘要。验签本身不能阻止重放：挑战必须有期限并由服务端原子单次消费。</p>
 *
 * @param challengeId 服务端生成的一次性挑战标识
 * @param nonce 服务端生成的 32 字节不可预测挑战值
 * @param deviceId 已配对设备的服务端标识
 * @param tenantId 设备绑定的可信租户标识
 * @param principalId 设备绑定的原主体标识
 * @param audience 配对时固定的服务端身份
 * @param method 服务端定义的规范请求方法
 * @param target 服务端定义的规范请求目标，须覆盖与操作相关的路径和查询
 * @param bodyDigest 服务端对实际请求正文计算的 32 字节 SHA-256 摘要
 */
public record DeviceRequestProof(
        UUID challengeId,
        byte[] nonce,
        UUID deviceId,
        UUID tenantId,
        String principalId,
        String audience,
        String method,
        String target,
        byte[] bodyDigest
) {
    /** 固定版本与用途，避免把同一签名解释成其他协议的证明。 */
    private static final String DOMAIN = "cm-agent-device-request-v1";

    /** 校验绑定字段并复制字节数组，避免签名内容在创建后被调用方改变。 */
    public DeviceRequestProof {
        Objects.requireNonNull(challengeId, "挑战标识不能为空");
        nonce = copyFixed(nonce, "挑战值");
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        Objects.requireNonNull(tenantId, "租户标识不能为空");
        requireText(principalId, "主体标识", 1024);
        requireText(audience, "服务端身份", 2048);
        requireText(method, "请求方法", 32);
        requireText(target, "请求目标", 8192);
        bodyDigest = copyFixed(bodyDigest, "正文摘要");
    }

    /** @return 挑战值副本，修改返回值不会改变签名内容 */
    @Override public byte[] nonce() { return nonce.clone(); }
    /** @return 正文摘要副本，修改返回值不会改变签名内容 */
    @Override public byte[] bodyDigest() { return bodyDigest.clone(); }

    /** 按挑战和请求的实际字节比较，避免数组引用身份影响证明对象的值语义。 */
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DeviceRequestProof that)) return false;
        return challengeId.equals(that.challengeId) && Arrays.equals(nonce, that.nonce)
                && deviceId.equals(that.deviceId) && tenantId.equals(that.tenantId)
                && principalId.equals(that.principalId) && audience.equals(that.audience)
                && method.equals(that.method) && target.equals(that.target)
                && Arrays.equals(bodyDigest, that.bodyDigest);
    }

    /** @return 与证明内容的值相符的哈希值 */
    @Override public int hashCode() {
        int result = Objects.hash(challengeId, deviceId, tenantId, principalId, audience, method, target);
        result = 31 * result + Arrays.hashCode(nonce);
        return 31 * result + Arrays.hashCode(bodyDigest);
    }

    /** 不在隐式日志或异常格式化中暴露请求目标、挑战值或正文摘要。 */
    @Override public String toString() {
        return "DeviceRequestProof[challengeId=" + challengeId + ", deviceId=" + deviceId + "]";
    }

    /**
     * 生成版本固定、长度明确的签名输入。
     *
     * @return 每次新建的字节数组，包含协议用途、挑战、身份及请求绑定
     */
    public byte[] canonicalBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeText(output, DOMAIN);
                writeUuid(output, challengeId);
                output.write(nonce);
                writeUuid(output, deviceId);
                writeUuid(output, tenantId);
                writeText(output, principalId);
                writeText(output, audience);
                writeText(output, method);
                writeText(output, target);
                output.write(bodyDigest);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            // 内存字节流不会执行外部 I/O；保留异常转换以符合 DataOutputStream 的受检异常合同。
            throw new IllegalStateException("无法编码设备证明", impossible);
        }
    }

    private static byte[] copyFixed(byte[] value, String name) {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException(name + "必须为 32 字节");
        }
        return value.clone();
    }

    private static void requireText(String value, String name, int maximum) {
        if (value == null || value.isBlank() || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IllegalArgumentException(name + "无效");
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }
}
