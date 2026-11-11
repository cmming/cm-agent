package com.cmagent.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * 已配对设备项目功能的独立部署开关和浏览器来源策略。
 *
 * <p>该开关与 Server 本机项目分离。远端功能只能连接已配对设备，默认关闭；
 * 非回环 HTTP 浏览器来源永不接受，即使显式放宽网络地址条件也仍须使用 HTTPS。</p>
 */
@ConfigurationProperties("cm-agent.client-projects")
public final class ClientProjectProperties {
    /** 是否开放已配对设备项目的新来源和接口。 */
    private boolean enabled;
    /** 是否允许非回环浏览器打开项目页；仅影响显式精确来源。 */
    private boolean allowNonLoopback;
    /** 精确浏览器来源，不接受通配符、路径、查询或片段。 */
    private List<String> allowedOrigins = List.of();
    /** 用户在目标设备确认根目录的最长时限。 */
    private Duration selectionTtl = Duration.ofMinutes(2);
    /** 项目级只读设备任务的最长完成时限。 */
    private Duration queryTtl = Duration.ofSeconds(30);
    /** 浏览器轮询任务状态的最长保留期限。 */
    private Duration statusRetention = Duration.ofMinutes(10);
    /** 单条命令计划和启动任务的领取/完成期限。 */
    private Duration commandTaskTtl = Duration.ofMinutes(35);
    /** 模型结束后等待设备命令收敛的独立屏障期限，不占用 Web 请求线程。 */
    private Duration commandBarrierTimeout = Duration.ofMinutes(5);
    /** 当前 Server 实例持有设备项目 Run 的数据库租期。 */
    private Duration ownershipLease = Duration.ofSeconds(30);
    /** 租约心跳、过期接管和恢复确认扫描间隔。 */
    private Duration recoveryScanInterval = Duration.ofSeconds(5);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isAllowNonLoopback() { return allowNonLoopback; }
    public void setAllowNonLoopback(boolean allowNonLoopback) { this.allowNonLoopback = allowNonLoopback; }
    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> value) {
        allowedOrigins = value == null ? List.of() : List.copyOf(value);
    }
    public Duration getSelectionTtl() { return selectionTtl; }
    public void setSelectionTtl(Duration value) { selectionTtl = value; }
    public Duration getQueryTtl() { return queryTtl; }
    public void setQueryTtl(Duration value) { queryTtl = value; }
    public Duration getStatusRetention() { return statusRetention; }
    public void setStatusRetention(Duration value) { statusRetention = value; }
    public Duration getCommandTaskTtl() { return commandTaskTtl; }
    public void setCommandTaskTtl(Duration value) { commandTaskTtl = value; }
    public Duration getCommandBarrierTimeout() { return commandBarrierTimeout; }
    public void setCommandBarrierTimeout(Duration value) { commandBarrierTimeout = value; }
    public Duration getOwnershipLease() { return ownershipLease; }
    public void setOwnershipLease(Duration value) { ownershipLease = value; }
    public Duration getRecoveryScanInterval() { return recoveryScanInterval; }
    public void setRecoveryScanInterval(Duration value) { recoveryScanInterval = value; }

    /**
     * 在功能启用时验证精确来源和短期期限；失败信息不包含客户端目录或凭据。
     */
    public void validate() {
        if (!enabled) return;
        if (allowedOrigins.isEmpty() || allowedOrigins.size() > 8) {
            throw new IllegalStateException("客户端项目必须配置1至8个精确浏览器来源");
        }
        for (String value : allowedOrigins) {
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException("客户端项目浏览器来源格式无效");
            }
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    || "http".equals(uri.getScheme()) && !LocalProjectProperties.loopbackHost(uri.getHost())
                    || !allowNonLoopback && !LocalProjectProperties.loopbackHost(uri.getHost())
                    || !value.equals(uri.getScheme() + "://" + uri.getRawAuthority())) {
                throw new IllegalStateException("客户端项目浏览器来源必须是精确 HTTP/HTTPS 来源");
            }
        }
        positive(selectionTtl, "selection-ttl", Duration.ofMinutes(5));
        positive(queryTtl, "query-ttl", Duration.ofMinutes(2));
        positive(statusRetention, "status-retention", Duration.ofHours(1));
        positive(commandTaskTtl, "command-task-ttl", Duration.ofMinutes(40));
        positive(commandBarrierTimeout, "command-barrier-timeout", Duration.ofMinutes(30));
        if (ownershipLease == null || ownershipLease.compareTo(Duration.ofSeconds(1)) < 0
                || ownershipLease.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException("cm-agent.client-projects.ownership-lease 必须在一秒至五分钟内");
        }
        if (recoveryScanInterval == null || recoveryScanInterval.compareTo(Duration.ofSeconds(1)) < 0
                || recoveryScanInterval.compareTo(ownershipLease) >= 0) {
            throw new IllegalStateException(
                    "cm-agent.client-projects.recovery-scan-interval 必须至少一秒且短于 ownership-lease");
        }
    }

    private static void positive(Duration value, String name, Duration maximum) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalStateException("cm-agent.client-projects." + name + " 超出允许范围");
        }
    }

    @Override public String toString() {
        return "ClientProjectProperties[enabled=" + enabled + ", 受保护的设备项目配置]";
    }
}
