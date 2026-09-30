package com.cmagent.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** 主动过期扫描配置；停用扫描仍保留人工过期处理和历史查询。 */
@ConfigurationProperties("cm-agent.approval-expiry")
public class ApprovalExpiryProperties {
    /** 默认启用，独立于新审批开关，关闭新审批后仍可清理历史等待。 */
    private boolean enabled = true;
    /** 每批结束后等待的时间，范围 1 秒～1 小时。 */
    private Duration interval = Duration.ofSeconds(30);
    /** 每轮最多发现的审批数量，范围 1～100。 */
    private int batchSize = 50;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getInterval() { return interval; }
    public void setInterval(Duration interval) { this.interval = interval; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    /** 即使关闭扫描也校验配置，防止后续启用时加载无界批次或零间隔。 */
    public void validate() {
        if (interval == null || interval.compareTo(Duration.ofSeconds(1)) < 0
                || interval.compareTo(Duration.ofHours(1)) > 0 || batchSize < 1 || batchSize > 100) {
            throw new IllegalStateException("审批过期扫描间隔必须在 1 秒～1 小时，批次大小必须在 1～100");
        }
    }
}
