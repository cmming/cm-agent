package com.cmagent.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * 浏览器所在电脑本机客户端的远程配对、挑战和操作等待参数。
 *
 * <p>服务端地址必须在部署时固定为客户端可访问的规范来源，不能用请求的 Host 头代替身份。
 * 关闭功能时不注册客户端开放协议，也不会向任何 Run 注入本机工具。</p>
 */
@ConfigurationProperties("cm-agent.local-client")
public final class LocalClientProperties {
    /** 是否启用本机客户端配对和签名任务通道。 */
    private boolean enabled;
    /** 服务端对外规范来源，例如 https://agent.example.com。 */
    private String audience = "http://127.0.0.1:8080";
    /** 浏览器确认配对的最长时限。 */
    private Duration pairingTtl = Duration.ofMinutes(10);
    /** 每次签到签名的挑战有效期。 */
    private Duration challengeTtl = Duration.ofSeconds(30);
    /** 单轮运行的最长有效时间，运行结束仍由 Run 状态单独复核。 */
    private Duration runGrantTtl = Duration.ofHours(2);
    /** Server 等待本机操作结果的最长时限。 */
    private Duration operationTimeout = Duration.ofSeconds(45);

    /** @return 是否启用本机客户端功能 */
    public boolean isEnabled() { return enabled; }
    /** @param enabled 是否启用本机客户端功能 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /** @return 配置的规范服务端身份 */
    public String getAudience() { return audience; }
    /** @param audience 客户端可访问的规范服务端来源 */
    public void setAudience(String audience) { this.audience = audience; }
    /** @return 配对确认时限 */
    public Duration getPairingTtl() { return pairingTtl; }
    /** @param pairingTtl 配对确认时限 */
    public void setPairingTtl(Duration pairingTtl) { this.pairingTtl = pairingTtl; }
    /** @return 挑战有效期 */
    public Duration getChallengeTtl() { return challengeTtl; }
    /** @param challengeTtl 挑战有效期 */
    public void setChallengeTtl(Duration challengeTtl) { this.challengeTtl = challengeTtl; }
    /** @return 单轮授权最长有效时间 */
    public Duration getRunGrantTtl() { return runGrantTtl; }
    /** @param runGrantTtl 单轮授权最长有效时间 */
    public void setRunGrantTtl(Duration runGrantTtl) { this.runGrantTtl = runGrantTtl; }
    /** @return 单次操作结果等待时间 */
    public Duration getOperationTimeout() { return operationTimeout; }
    /** @param operationTimeout 单次操作结果等待时间 */
    public void setOperationTimeout(Duration operationTimeout) { this.operationTimeout = operationTimeout; }

    /** 在启用时校验规范来源和所有短期限。 */
    public void validate() {
        if (!enabled) return;
        URI parsed;
        try {
            parsed = URI.create(audience);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("cm-agent.local-client.audience 不是有效 URI", failure);
        }
        if (!parsed.isAbsolute() || parsed.getHost() == null
                || !("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme()))
                || parsed.getQuery() != null || parsed.getFragment() != null) {
            throw new IllegalStateException("cm-agent.local-client.audience 必须是 HTTP/HTTPS 规范来源");
        }
        if ("http".equals(parsed.getScheme()) && !isLoopback(parsed.getHost())) {
            throw new IllegalStateException("非回环本机客户端通道必须使用 HTTPS");
        }
        positive(pairingTtl, "pairing-ttl", Duration.ofMinutes(30));
        positive(challengeTtl, "challenge-ttl", Duration.ofMinutes(2));
        positive(runGrantTtl, "run-grant-ttl", Duration.ofHours(24));
        positive(operationTimeout, "operation-timeout", Duration.ofMinutes(5));
    }

    private static void positive(Duration value, String name, Duration maximum) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalStateException("cm-agent.local-client." + name + " 超出允许范围");
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host)
                || "[::1]".equals(host);
    }
}
