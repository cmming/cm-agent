package com.cmagent.core.security;

import com.cmagent.core.domain.LocalClientChallenge;
import com.cmagent.core.domain.LocalClientDevice;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * 使用服务端可信设备和挑战记录验证本机客户端的持钥证明。
 *
 * <p>本类不读取仓储，也不消费挑战或授予运行权限。调用方必须从可信仓储读取记录，在验签后
 * 通过同一事务原子消费挑战并登记派发，同时复核运行授权、设备撤销和严格审计。</p>
 */
public final class LocalClientRequestVerifier {
    private LocalClientRequestVerifier() { }

    /**
     * 检查设备归属、挑战时限及请求绑定签名。
     *
     * <p>服务端身份、请求方法、目标和正文摘要必须由服务端依据实际连接和请求重建，不能使用
     * 客户端声称的值替代。此方法即使返回 {@code true}，同一挑战也仍需由仓储单次消费。</p>
     *
     * @param device 从可信仓储读取的设备绑定
     * @param challenge 从可信仓储读取的未消费挑战
     * @param audience 服务端固定的规范身份
     * @param method 实际请求的规范方法
     * @param target 实际请求的规范目标
     * @param bodyDigest 服务端根据实际正文计算的 SHA-256 摘要
     * @param signature 客户端提交的设备签名
     * @param now 服务端可信时间
     * @return 设备归属、时限和签名均匹配时为 {@code true}
     * @throws GeneralSecurityException 本机公钥解析或签名验证基础设施不可用
     */
    public static boolean verify(LocalClientDevice device, LocalClientChallenge challenge, String audience,
                                 String method, String target, byte[] bodyDigest, byte[] signature, Instant now)
            throws GeneralSecurityException {
        Objects.requireNonNull(device, "设备不能为空");
        Objects.requireNonNull(challenge, "挑战不能为空");
        Objects.requireNonNull(now, "当前时间不能为空");
        if (device.state() != LocalClientDevice.State.ACTIVE
                || !device.id().equals(challenge.deviceId())
                || !device.tenantId().equals(challenge.tenantId())
                || !device.ownerId().equals(challenge.ownerId())
                || now.isBefore(challenge.createdAt()) || !now.isBefore(challenge.expiresAt())) {
            return false;
        }
        var proof = new DeviceRequestProof(challenge.id(), challenge.nonce(), device.id(), device.tenantId(),
                device.ownerId(), audience, method, target, bodyDigest);
        var key = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(device.publicKey())));
        return DeviceProofSignatures.verify(proof, key, signature);
    }
}
