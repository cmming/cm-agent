package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 服务端为设备签名验证创建的单次挑战内容。
 *
 * <p>该对象不表示挑战仍未使用；调用方必须在同一可信仓储中校验设备状态、时间并原子消费。
 * 挑战值必须由服务端的密码学安全随机源生成，不能由请求或模型指定。</p>
 *
 * @param id 服务端生成的挑战标识
 * @param tenantId 绑定设备的可信租户
 * @param ownerId 绑定设备的原主体
 * @param deviceId 需要证明持钥的已配对设备
 * @param nonce 服务端生成的 32 字节随机值，构造和访问时均复制
 * @param createdAt 服务端创建时间
 * @param expiresAt 服务端设定的排他过期时间
 */
public record LocalClientChallenge(
        UUID id,
        UUID tenantId,
        String ownerId,
        UUID deviceId,
        byte[] nonce,
        Instant createdAt,
        Instant expiresAt
) {
    /** 固定挑战的归属与时限，防止外部引用改变随机值。 */
    public LocalClientChallenge {
        Objects.requireNonNull(id, "挑战标识不能为空");
        Objects.requireNonNull(tenantId, "租户标识不能为空");
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 200) {
            throw new IllegalArgumentException("挑战原主体无效");
        }
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        if (nonce == null || nonce.length != 32) throw new IllegalArgumentException("挑战值必须为 32 字节");
        nonce = nonce.clone();
        Objects.requireNonNull(createdAt, "创建时间不能为空");
        Objects.requireNonNull(expiresAt, "过期时间不能为空");
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("挑战过期时间无效");
    }

    /** @return 挑战值副本 */
    @Override public byte[] nonce() { return nonce.clone(); }

    /** 按挑战值的实际字节比较，避免数组引用身份改变领域对象的值语义。 */
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LocalClientChallenge that)) return false;
        return id.equals(that.id) && tenantId.equals(that.tenantId) && ownerId.equals(that.ownerId)
                && deviceId.equals(that.deviceId) && Arrays.equals(nonce, that.nonce)
                && createdAt.equals(that.createdAt) && expiresAt.equals(that.expiresAt);
    }

    /** @return 与挑战内容的值相符的哈希值 */
    @Override public int hashCode() {
        return 31 * Objects.hash(id, tenantId, ownerId, deviceId, createdAt, expiresAt) + Arrays.hashCode(nonce);
    }

    /** 不在隐式日志或异常格式化中输出随机挑战值。 */
    @Override public String toString() { return "LocalClientChallenge[id=" + id + ", deviceId=" + deviceId + "]"; }
}
