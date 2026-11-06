package com.cmagent.server.runtime;

import com.cmagent.core.domain.LocalClientChallenge;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.repository.LocalClientDeviceRepository;
import com.cmagent.core.security.LocalClientRequestVerifier;
import com.cmagent.server.config.LocalClientProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 为未登录本机客户端签发短期挑战并核验绑定具体请求的设备签名。 */
@Service
@ConditionalOnProperty(prefix = "cm-agent.local-client", name = "enabled", havingValue = "true")
public class LocalClientProofService {
    private static final long RATE_WINDOW_MILLIS = 60_000;
    private static final int RATE_LIMIT = 120;
    private final LocalClientDeviceRepository devices;
    private final LocalClientProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, RateWindow> rates = new ConcurrentHashMap<>();

    /**
     * @param devices 设备与挑战仓储
     * @param properties 已校验的挑战和来源配置
     */
    // 显式选择生产构造器，避免与注入测试时钟的构造器产生歧义。
    @org.springframework.beans.factory.annotation.Autowired
    public LocalClientProofService(LocalClientDeviceRepository devices, LocalClientProperties properties) {
        this(devices, properties, Clock.systemUTC());
    }

    LocalClientProofService(LocalClientDeviceRepository devices, LocalClientProperties properties, Clock clock) {
        this.devices = Objects.requireNonNull(devices, "devices 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 为活动设备签发单次挑战。
     *
     * @param deviceId 客户端声明的设备标识
     * @param source 客户端或浏览器的远程地址摘要，用于限制匿名挑战洪泛
     * @return 挑战公开字段和随机值 Base64 编码
     */
    public ChallengeView challenge(UUID deviceId, String source) {
        rate(source);
        LocalClientDevice device = devices.findAnyById(deviceId).orElse(null);
        if (device == null || device.state() != LocalClientDevice.State.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在或已撤销");
        }
        Instant now = clock.instant();
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        var challenge = new LocalClientChallenge(UUID.randomUUID(), device.tenantId(), device.ownerId(),
                device.id(), nonce, now, now.plus(properties.getChallengeTtl()));
        if (!devices.insertChallenge(challenge)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备状态已变化");
        }
        return new ChallengeView(challenge.id(), java.util.Base64.getEncoder().encodeToString(challenge.nonce()),
                challenge.expiresAt());
    }

    /**
     * 验证设备签名并原子消费挑战。
     *
     * @param deviceId 设备标识
     * @param challengeId 挑战标识
     * @param signatureBase64 Ed25519 签名 Base64 编码
     * @param method 实际 HTTP 方法
     * @param target 实际规范路径
     * @param body 实际请求正文
     * @param source 客户端远程地址摘要
     * @return 已完成本次挑战证明的活动设备
     */
    public LocalClientDevice verifyAndConsume(UUID deviceId, UUID challengeId, String signatureBase64,
            String method, String target, byte[] body, String source) {
        rate(source);
        LocalClientDevice device = devices.findAnyById(deviceId).orElse(null);
        if (device == null || device.state() != LocalClientDevice.State.ACTIVE) {
            throw denied();
        }
        Instant now = clock.instant();
        LocalClientChallenge challenge = devices.findChallenge(device.tenantId(), device.ownerId(),
                device.id(), challengeId, now).orElse(null);
        if (challenge == null) throw denied();
        byte[] signature;
        try {
            signature = java.util.Base64.getDecoder().decode(
                    Objects.requireNonNullElse(signatureBase64, ""));
        } catch (IllegalArgumentException invalid) {
            throw denied();
        }
        try {
            if (!LocalClientRequestVerifier.verify(device, challenge, properties.getAudience(),
                    method, target, sha256(body), signature, now)) {
                throw denied();
            }
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw denied();
        }
        if (!devices.consumeChallenge(device.tenantId(), device.ownerId(), device.id(), challenge.id(), now)) {
            throw denied();
        }
        return device;
    }

    /** 请求正文摘要始终使用 SHA-256，调用方不能复用设备声明的摘要。 */
    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNullElse(body, new byte[0]));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    /** 固定窗口限制仅约束匿名挑战和签名端点，不替代设备密钥验证。 */
    private void rate(String source) {
        long now = clock.millis();
        String key = source == null || source.isBlank() ? "unknown" : source;
        rates.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAtMillis >= RATE_WINDOW_MILLIS) {
                return new RateWindow(now, 1);
            }
            if (current.count >= RATE_LIMIT) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "本机客户端请求过于频繁");
            }
            return new RateWindow(current.startedAtMillis, current.count + 1);
        });
        if (rates.size() > 10_000) {
            rates.entrySet().removeIf(entry -> now - entry.getValue().startedAtMillis >= RATE_WINDOW_MILLIS);
        }
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "设备签名无效或挑战已使用");
    }

    private record RateWindow(long startedAtMillis, int count) { }

    /**
     * 挑战响应只包含客户端签名所需字段。
     *
     * @param challengeId 挑战标识
     * @param nonce 三十二字节随机值 Base64 编码
     * @param expiresAt 排他过期时间
     */
    public record ChallengeView(UUID challengeId, String nonce, Instant expiresAt) { }
}
