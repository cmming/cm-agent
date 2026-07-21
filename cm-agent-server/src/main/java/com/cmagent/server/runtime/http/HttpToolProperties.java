package com.cmagent.server.runtime.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "cm-agent.http-tools")
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowHttp() {
        return allowHttp;
    }

    public void setAllowHttp(boolean allowHttp) {
        this.allowHttp = allowHttp;
    }

    public Set<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(Set<String> allowedHosts) {
        this.allowedHosts = Set.copyOf(new LinkedHashSet<>(allowedHosts == null ? Set.of() : allowedHosts));
    }

    public Map<String, String> getSecrets() {
        return secrets;
    }

    public void setSecrets(Map<String, String> secrets) {
        this.secrets = Map.copyOf(new LinkedHashMap<>(secrets == null ? Map.of() : secrets));
    }

    public Duration getMinTimeout() {
        return minTimeout;
    }

    public void setMinTimeout(Duration minTimeout) {
        validateTimeoutBound(minTimeout);
        this.minTimeout = minTimeout;
    }

    public Duration getMaxTimeout() {
        return maxTimeout;
    }

    public void setMaxTimeout(Duration maxTimeout) {
        validateTimeoutBound(maxTimeout);
        this.maxTimeout = maxTimeout;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        if (maxResponseBytes < 1 || maxResponseBytes > ABSOLUTE_MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("maxResponseBytes 必须在 1 到 262144 之间");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

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
