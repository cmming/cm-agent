package com.cmagent.core.security;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Objects;

/**
 * 设备持钥证明的 Ed25519 签名与验签原语。
 *
 * <p>仅验签不能证明设备未撤销、租户有权访问或挑战尚未使用；服务端必须读取可信设备记录并
 * 原子消费有效挑战。本类不生成、保存或记录设备私钥，也不维护共享的可变签名实例。</p>
 */
public final class DeviceProofSignatures {
    private DeviceProofSignatures() { }

    /**
     * 使用设备私钥签名服务端挑战和具体请求。
     *
     * @param proof 已完成字段校验的证明内容
     * @param privateKey 仅由本机客户端持有的 Ed25519 私钥
     * @return 新建的签名字节数组
     * @throws GeneralSecurityException 密钥不可用或签名实现失败
     */
    public static byte[] sign(DeviceRequestProof proof, PrivateKey privateKey) throws GeneralSecurityException {
        Objects.requireNonNull(proof, "设备证明不能为空");
        Objects.requireNonNull(privateKey, "设备私钥不能为空");
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(proof.canonicalBytes());
        return signer.sign();
    }

    /**
     * 根据已登记设备公钥验证服务端重建的证明。
     *
     * @param proof 服务端依据可信记录和实际请求重建的证明
     * @param publicKey 已配对设备的可信 Ed25519 公钥
     * @param signature 客户端提交的签名
     * @return 签名与证明完全匹配时为 {@code true}
     * @throws GeneralSecurityException 公钥不可用或验签实现初始化失败
     */
    public static boolean verify(DeviceRequestProof proof, PublicKey publicKey, byte[] signature)
            throws GeneralSecurityException {
        Objects.requireNonNull(proof, "设备证明不能为空");
        Objects.requireNonNull(publicKey, "设备公钥不能为空");
        if (signature == null || signature.length != 64) {
            return false;
        }
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(proof.canonicalBytes());
        try {
            return verifier.verify(signature);
        } catch (SignatureException invalid) {
            // 非法签名属于普通拒绝，不能把原始签名内容传给调用方或日志。
            return false;
        }
    }
}
