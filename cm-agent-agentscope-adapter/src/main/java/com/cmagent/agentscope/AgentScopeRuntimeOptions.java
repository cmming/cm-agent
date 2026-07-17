package com.cmagent.agentscope;

import java.time.Duration;
import java.util.Objects;

public record AgentScopeRuntimeOptions(Duration modelTimeout, Duration toolTimeout, int modelMaxAttempts) {

    public AgentScopeRuntimeOptions {
        Objects.requireNonNull(modelTimeout, "modelTimeout 不能为空");
        Objects.requireNonNull(toolTimeout, "toolTimeout 不能为空");
        if (modelTimeout.isZero() || modelTimeout.isNegative()) {
            throw new IllegalArgumentException("模型超时必须大于 0");
        }
        if (toolTimeout.isZero() || toolTimeout.isNegative()) {
            throw new IllegalArgumentException("工具超时必须大于 0");
        }
        if (modelMaxAttempts < 1 || modelMaxAttempts > 5) {
            throw new IllegalArgumentException("模型最大尝试次数必须在 1 到 5 之间");
        }
    }
}
