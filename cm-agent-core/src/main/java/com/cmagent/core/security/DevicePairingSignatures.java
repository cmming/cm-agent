package com.cmagent.core.security;

import com.cmagent.core.domain.LocalClientDevice;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * 设备首次配对时的 Ed25519 持钥证明。
 *
 * <p>签名同时绑定协议用途、配对请求标识、随机值、平台、名称和公钥，浏览器不能在不持有私钥时
 * 替换任意字段。配对包只证明客户端持有私钥；浏览器仍须用已认证主体明确确认，配对成功后的每个
 * 远程请求还必须使用服务端生成的一次性挑战签名。</p>
 */
public final class DevicePairingSignatures {
    private static final String DOMAIN = "cm-agent-device-pairing-v1";

    private DevicePairingSignatures() { }

    /**
     * 对客户端生成的配对包签名。
     *
     * @param requestId 客户端生成的随机请求标识
     * @param nonce 客户端生成的三十二字节随机值
     * @param deviceName 供用户核对的设备名称
     * @param platform 客户端平台
     * @param publicKey 与私钥配对的 X.509 公钥 Base64 编码
     * @param privateKey 客户端系统凭据存储中保护的 Ed25519 私钥
     * @return 六十四字节签名
     * @throws GeneralSecurityException 私钥或签名实现不可用
     */
    public static byte[] sign(UUID requestId, byte[] nonce, String deviceName,
                              LocalClientDevice.Platform platform, String publicKey, PrivateKey privateKey)
            throws GeneralSecurityException {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(Objects.requireNonNull(privateKey, "设备私钥不能为空"));
        signer.update(canonicalBytes(requestId, nonce, deviceName, platform, publicKey));
        return signer.sign();
    }

    /**
     * 使用配对包中的公钥验证客户端持钥证明。
     *
     * @param requestId 配对请求标识
     * @param nonce 三十二字节随机值
     * @param deviceName 设备名称
     * @param platform 客户端平台
     * @param publicKey 公钥 Base64 编码
     * @param proof 客户端签名
     * @return 签名、字段和公钥均有效时为 {@code true}
     * @throws GeneralSecurityException 公钥或验签实现不可用
     */
    public static boolean verify(UUID requestId, byte[] nonce, String deviceName,
                                 LocalClientDevice.Platform platform, String publicKey, byte[] proof)
            throws GeneralSecurityException {
        if (proof == null || proof.length != 64) return false;
        byte[] encoded = Base64.getDecoder().decode(publicKey);
        PublicKey key = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(encoded));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(canonicalBytes(requestId, nonce, deviceName, platform, publicKey));
        try {
            return verifier.verify(proof);
        } catch (SignatureException invalid) {
            // 非法签名属于普通拒绝，不把签名正文或底层实现异常传到浏览器。
            return false;
        }
    }

    private static byte[] canonicalBytes(UUID requestId, byte[] nonce, String deviceName,
                                         LocalClientDevice.Platform platform, String publicKey) {
        Objects.requireNonNull(requestId, "配对请求标识不能为空");
        if (nonce == null || nonce.length != 32) throw new IllegalArgumentException("配对随机值无效");
        requireText(deviceName, "设备名称", 120);
        Objects.requireNonNull(platform, "设备平台不能为空");
        requireText(publicKey, "设备公钥", 512);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeText(output, DOMAIN);
                output.writeLong(requestId.getMostSignificantBits());
                output.writeLong(requestId.getLeastSignificantBits());
                output.write(nonce);
                writeText(output, platform.name());
                writeText(output, deviceName);
                writeText(output, publicKey);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("无法编码设备配对证明", impossible);
        }
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
}
