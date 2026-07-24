package com.cmagent.server.runtime.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "cm-agent.http-tools")
/** 动态 HTTP 工具的运行时安全限制和网络访问配置。 */
public class HttpToolProperties {
    private static final Duration ABSOLUTE_MIN_TIMEOUT = Duration.ofMillis(100);
    private static final Duration ABSOLUTE_MAX_TIMEOUT = Duration.ofSeconds(30);
    private static final int ABSOLUTE_MAX_RESPONSE_BYTES = 262_144;
    private static final int ABSOLUTE_MAX_REDIRECTS = 3;

    private boolean enabled;
    private boolean allowHttp;
    private Set<String> allowedHosts = Set.of();
    private Map<String, String> secrets = Map.of();
    private Duration minTimeout = Duration.ofMillis(100);
    private Duration maxTimeout = Duration.ofSeconds(30);
    private int maxResponseBytes = 262_144;
    private int maxRedirects = 3;

    /** @return 是否启用动态 HTTP 工具执行。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled 是否启用动态 HTTP 工具执行。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return 是否允许使用明文 HTTP 协议。 */
    public boolean isAllowHttp() {
        return allowHttp;
    }

    /** @param allowHttp 是否允许使用明文 HTTP 协议。 */
    public void setAllowHttp(boolean allowHttp) {
        this.allowHttp = allowHttp;
    }

    /** @return 允许访问的目标主机白名单。 */
    public Set<String> getAllowedHosts() {
        return allowedHosts;
    }

    /** @param allowedHosts 允许访问的目标主机白名单，将被复制为不可变集合。 */
    public void setAllowedHosts(Set<String> allowedHosts) {
        this.allowedHosts = Set.copyOf(new LinkedHashSet<>(allowedHosts == null ? Set.of() : allowedHosts));
    }

    /** @return secret 引用到实际值的映射；调用方不得记录返回内容。 */
    public Map<String, String> getSecrets() {
        return secrets;
    }

    /** @param secrets secret 引用到实际值的映射，将被复制为不可变映射。 */
    public void setSecrets(Map<String, String> secrets) {
        this.secrets = Map.copyOf(new LinkedHashMap<>(secrets == null ? Map.of() : secrets));
    }

    /** @return HTTP 请求允许的最小超时时间。 */
    public Duration getMinTimeout() {
        return minTimeout;
    }

    /**
     * 设置 HTTP 请求最小超时时间。
     *
     * @param minTimeout 最小超时时间，必须在 100 毫秒到 30 秒之间
     * @throws IllegalArgumentException 超出允许范围时抛出
     */
    public void setMinTimeout(Duration minTimeout) {
        validateTimeoutBound(minTimeout);
        this.minTimeout = minTimeout;
    }

    /** @return HTTP 请求允许的最大超时时间。 */
    public Duration getMaxTimeout() {
        return maxTimeout;
    }

    /**
     * 设置 HTTP 请求最大超时时间。
     *
     * @param maxTimeout 最大超时时间，必须在 100 毫秒到 30 秒之间
     * @throws IllegalArgumentException 超出允许范围时抛出
     */
    public void setMaxTimeout(Duration maxTimeout) {
        validateTimeoutBound(maxTimeout);
        this.maxTimeout = maxTimeout;
    }

    /** @return HTTP 响应允许的最大字节数。 */
    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    /**
     * 设置 HTTP 响应最大字节数。
     *
     * @param maxResponseBytes 最大响应字节数，范围为 1 到 262144
     * @throws IllegalArgumentException 超出允许范围时抛出
     */
    public void setMaxResponseBytes(int maxResponseBytes) {
        if (maxResponseBytes < 1 || maxResponseBytes > ABSOLUTE_MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("maxResponseBytes 必须在 1 到 262144 之间");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    /** @return HTTP 请求允许的最大重定向次数。 */
    public int getMaxRedirects() {
        return maxRedirects;
    }

    /**
     * 设置 HTTP 请求最大重定向次数。
     *
     * @param maxRedirects 最大重定向次数，范围为 0 到 3
     * @throws IllegalArgumentException 超出允许范围时抛出
     */
    public void setMaxRedirects(int maxRedirects) {
        if (maxRedirects < 0 || maxRedirects > ABSOLUTE_MAX_REDIRECTS) {
            throw new IllegalArgumentException("maxRedirects 必须在 0 到 3 之间");
        }
        this.maxRedirects = maxRedirects;
    }

    private static void validateTimeoutBound(Duration timeout) {
        if (timeout == null || timeout.compareTo(ABSOLUTE_MIN_TIMEOUT) < 0
                || timeout.compareTo(ABSOLUTE_MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("HTTP timeout 边界必须在 100ms 到 30s 之间");
        }
    }

    @Override
    public String toString() {
        return "HttpToolProperties{" +
                "enabled=" + enabled +
                ", allowHttp=" + allowHttp +
                ", allowedHosts=" + allowedHosts +
                ", secrets=<已脱敏:" + secrets.size() + ">" +
                ", minTimeout=" + minTimeout +
                ", maxTimeout=" + maxTimeout +
                ", maxResponseBytes=" + maxResponseBytes +
                ", maxRedirects=" + maxRedirects +
                '}';
    }
}
