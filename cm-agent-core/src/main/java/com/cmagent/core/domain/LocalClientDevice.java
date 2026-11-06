package com.cmagent.core.domain;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * 已经由原主体和本机确认配对的客户端设备记录。
 *
 * <p>公钥只用于验证设备持钥，不可作为认证主体或设备仍在线的证明；服务端必须从可信租户和
 * 原主体范围查询本记录，并在每次派发前复核状态。</p>
 *
 * @param id 服务端生成的设备标识
 * @param tenantId 认证上下文中的可信租户
 * @param ownerId 完成配对的原主体，必须逐字匹配
 * @param displayName 供用户核对的设备展示名，不参与授权匹配
 * @param platform 客户端声明并经配对流程展示的平台类别
 * @param publicKey 已验证的 Ed25519 X.509 公钥的 Base64 编码
 * @param state 配对状态，撤销后不能继续使用
 * @param version 条件更新版本，从一开始
 * @param createdAt 服务端记录的配对时间
 * @param updatedAt 服务端记录的最近状态变更时间
 */
public record LocalClientDevice(
        UUID id,
        UUID tenantId,
        String ownerId,
        String displayName,
        Platform platform,
        String publicKey,
        State state,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    /** 支持配对的客户端操作系统类别，不作为识别具体设备的凭据。 */
    public enum Platform {
        /** Windows 本机客户端。 */ WINDOWS,
        /** macOS 本机客户端。 */ MACOS,
        /** Linux 本机客户端。 */ LINUX
    }

    /** 服务端对设备配对关系维护的状态。 */
    public enum State {
        /** 配对有效，仍须逐次验证设备持钥及本轮授权。 */ ACTIVE,
        /** 设备已撤销，不能用于新的挑战或操作派发。 */ REVOKED
    }

    /** 校验固定归属、状态版本及可用于 Ed25519 验证的公钥。 */
    public LocalClientDevice {
        Objects.requireNonNull(id, "设备标识不能为空");
        Objects.requireNonNull(tenantId, "租户标识不能为空");
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 200) {
            throw new IllegalArgumentException("设备原主体无效");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 120) {
            throw new IllegalArgumentException("设备展示名无效");
        }
        Objects.requireNonNull(platform, "设备平台不能为空");
        validatePublicKey(publicKey);
        Objects.requireNonNull(state, "设备状态不能为空");
        if (version < 1) throw new IllegalArgumentException("设备版本必须大于零");
        Objects.requireNonNull(createdAt, "配对时间不能为空");
        Objects.requireNonNull(updatedAt, "更新时间不能为空");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("设备更新时间无效");
    }

    /** 不在隐式日志中暴露设备展示名、主体或公钥。 */
    @Override public String toString() { return "LocalClientDevice[id=" + id + ", state=" + state + "]"; }

    private static void validatePublicKey(String value) {
        if (value == null || value.length() > 512) throw new IllegalArgumentException("设备公钥无效");
        try {
            byte[] encoded = Base64.getDecoder().decode(value);
            KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (IllegalArgumentException | GeneralSecurityException failure) {
            // 只返回固定原因，不回显未经认证的公钥或依赖实现异常。
            throw new IllegalArgumentException("设备公钥无效");
        }
    }
}
