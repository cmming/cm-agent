package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 由已登录浏览器确认的原主体与一台本机客户端之间的短期配对会话。
 *
 * <p>配对包由客户端生成并对公钥、平台、名称、随机值和请求标识签名；浏览器只能转交该包，
 * 不能替换设备身份。本记录只有在浏览器确认后才形成活动设备，客户端仍须使用原私钥完成后续
 * 单次挑战证明。</p>
 *
 * @param id 客户端生成并由服务端重新校验的配对请求标识
 * @param tenantId 确认配对的认证主体所属租户
 * @param ownerId 确认配对的认证主体标识
 * @param deviceName 供用户核对的设备展示名称
 * @param platform 客户端生成的平台类别
 * @param publicKey 客户端 Ed25519 公钥的 X.509 Base64 编码
 * @param nonce 客户端生成的三十二字节随机值
 * @param proof 客户端对配对包的 Ed25519 签名
 * @param state 待确认、已确认或已过期状态
 * @param deviceId 确认后生成的活动设备标识；待确认时为空
 * @param createdAt 服务端接收配对包的时间
 * @param expiresAt 浏览器必须在此之前确认的排他时间上限
 * @param confirmedAt 浏览器确认时间；待确认时为空
 */
public record LocalClientPairing(
        UUID id,
        UUID tenantId,
        String ownerId,
        String deviceName,
        LocalClientDevice.Platform platform,
        String publicKey,
        byte[] nonce,
        byte[] proof,
        State state,
        UUID deviceId,
        Instant createdAt,
        Instant expiresAt,
        Instant confirmedAt
) {
    /** 配对会话在服务端维护的生命周期状态。 */
    public enum State {
        /** 已收到有效客户端配对包，等待已登录浏览器确认。 */ PENDING,
        /** 已生成活动设备，设备私钥仍须逐次证明。 */ CONFIRMED,
        /** 已超过确认期限，不能转为活动设备。 */ EXPIRED
    }

    /** 校验租户、主体、设备公钥及配对状态的时间一致性。 */
    public LocalClientPairing {
        Objects.requireNonNull(id, "配对请求标识不能为空");
        Objects.requireNonNull(tenantId, "租户标识不能为空");
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 200) {
            throw new IllegalArgumentException("配对主体无效");
        }
        if (deviceName == null || deviceName.isBlank() || deviceName.length() > 120) {
            throw new IllegalArgumentException("设备名称无效");
        }
        Objects.requireNonNull(platform, "设备平台不能为空");
        if (publicKey == null || publicKey.isBlank() || publicKey.length() > 512) {
            throw new IllegalArgumentException("设备公钥无效");
        }
        if (nonce == null || nonce.length != 32) {
            throw new IllegalArgumentException("配对随机值必须为 32 字节");
        }
        nonce = nonce.clone();
        if (proof == null || proof.length != 64) {
            throw new IllegalArgumentException("配对签名无效");
        }
        proof = proof.clone();
        Objects.requireNonNull(state, "配对状态不能为空");
        Objects.requireNonNull(createdAt, "配对创建时间不能为空");
        Objects.requireNonNull(expiresAt, "配对过期时间不能为空");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("配对过期时间无效");
        }
        if (state == State.CONFIRMED && (deviceId == null || confirmedAt == null)) {
            throw new IllegalArgumentException("已确认配对缺少设备或确认时间");
        }
        if (state != State.CONFIRMED && deviceId != null) {
            throw new IllegalArgumentException("未确认配对不能携带设备标识");
        }
        if (confirmedAt != null && (confirmedAt.isBefore(createdAt) || confirmedAt.isAfter(expiresAt))) {
            throw new IllegalArgumentException("配对确认时间无效");
        }
    }

    /** @return 配对随机值副本 */
    @Override public byte[] nonce() { return nonce.clone(); }

    /** @return 配对签名副本 */
    @Override public byte[] proof() { return proof.clone(); }

    /** 按非秘密配对字段和签名字节比较，避免数组引用身份改变值语义。 */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LocalClientPairing that)) return false;
        return id.equals(that.id) && tenantId.equals(that.tenantId) && ownerId.equals(that.ownerId)
                && deviceName.equals(that.deviceName) && platform == that.platform
                && publicKey.equals(that.publicKey) && Arrays.equals(nonce, that.nonce)
                && Arrays.equals(proof, that.proof) && state == that.state
                && Objects.equals(deviceId, that.deviceId) && createdAt.equals(that.createdAt)
                && expiresAt.equals(that.expiresAt) && Objects.equals(confirmedAt, that.confirmedAt);
    }

    /** @return 与配对字段实际值相符的哈希值 */
    @Override
    public int hashCode() {
        int result = Objects.hash(id, tenantId, ownerId, deviceName, platform, publicKey, state,
                deviceId, createdAt, expiresAt, confirmedAt);
        result = 31 * result + Arrays.hashCode(nonce);
        return 31 * result + Arrays.hashCode(proof);
    }

    /** 不在日志或异常格式化中输出公钥、签名和随机值。 */
    @Override
    public String toString() {
        return "LocalClientPairing[id=" + id + ", state=" + state
                + ", deviceId=" + deviceId + "]";
    }
}
