package com.cmagent.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
/** AgentScope 运行时开关及调用控制配置属性。 */
@ConfigurationProperties(prefix = "cm-agent.agentscope")
public class AgentScopeRuntimeProperties {

    private boolean enabled;
    private Duration modelTimeout = Duration.ofSeconds(60);
    private Duration toolTimeout = Duration.ofSeconds(30);
    private int modelMaxAttempts = 2;
    /** 默认关闭以保持升级兼容；开启后 HIGH 工具进入 AgentScope ASK。 */
    private boolean permissionEnabled;
    /** 审批请求与可恢复检查点共享的有效期，默认十五分钟。 */
    private Duration approvalTtl = Duration.ofMinutes(15);

    /**
     * @return 是否启用 AgentScope 真实运行时。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param enabled 是否启用 AgentScope 真实运行时。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return 模型调用超时时间。
     */
    public Duration getModelTimeout() {
        return modelTimeout;
    }

    /**
     * @param modelTimeout 模型调用超时时间，必须为正数。
     */
    public void setModelTimeout(Duration modelTimeout) {
        this.modelTimeout = modelTimeout;
    }

    /**
     * @return 工具调用超时时间。
     */
    public Duration getToolTimeout() {
        return toolTimeout;
    }

    /**
     * @param toolTimeout 工具调用超时时间，必须为正数。
     */
    public void setToolTimeout(Duration toolTimeout) {
        this.toolTimeout = toolTimeout;
    }

    /**
     * @return 模型调用最大尝试次数。
     */
    public int getModelMaxAttempts() {
        return modelMaxAttempts;
    }

    /**
     * @param modelMaxAttempts 模型调用最大尝试次数，范围为 1 到 5。
     */
    public void setModelMaxAttempts(int modelMaxAttempts) {
        this.modelMaxAttempts = modelMaxAttempts;
    }

    /** @return 是否启用 HIGH 工具逐调用审批。 */
    public boolean isPermissionEnabled() {
        return permissionEnabled;
    }

    /** @param permissionEnabled 是否启用 HIGH 工具逐调用审批。 */
    public void setPermissionEnabled(boolean permissionEnabled) {
        this.permissionEnabled = permissionEnabled;
    }

    /** @return 待审批请求和运行检查点的有效期。 */
    public Duration getApprovalTtl() {
        return approvalTtl;
    }

    /** @param approvalTtl 审批有效期，必须为正数且不超过 24 小时。 */
    public void setApprovalTtl(Duration approvalTtl) {
        this.approvalTtl = approvalTtl;
    }

    /**
     * 校验运行时开关、超时和重试次数。
     *
     * @param fakeRuntimeEnabled 是否同时启用了 fake runtime
     * @throws IllegalStateException 配置不合法或真实运行时与 fake runtime 同时启用时抛出
     */
    public void validate(boolean fakeRuntimeEnabled) {
        if (!isPositive(modelTimeout)) {
            throw new IllegalStateException("模型超时时间必须为正数");
        }
        if (!isPositive(toolTimeout)) {
            throw new IllegalStateException("工具超时时间必须为正数");
        }
        if (modelMaxAttempts < 1 || modelMaxAttempts > 5) {
            throw new IllegalStateException("模型最大尝试次数必须在 1 到 5 之间");
        }
        if (!isPositive(approvalTtl) || approvalTtl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalStateException("审批有效期必须大于 0 且不超过 24 小时");
        }
        if (enabled && fakeRuntimeEnabled) {
            throw new IllegalStateException("AgentScope 真实运行时与 fake runtime 不能同时启用");
        }
    }

    /**
     * 判断方法名所描述的业务条件是否成立。
     *
     * @param duration 待校验并换算的超时时长
     */
    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    @Override
    /**
     * 返回不暴露凭据等敏感字段的安全文本摘要。
     */
    public String toString() {
        return "AgentScopeRuntimeProperties[enabled=" + enabled
                + ", modelTimeout=" + modelTimeout
                + ", toolTimeout=" + toolTimeout
                + ", modelMaxAttempts=" + modelMaxAttempts
                + ", permissionEnabled=" + permissionEnabled
                + ", approvalTtl=" + approvalTtl + "]";
    }
}
