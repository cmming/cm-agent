package com.cmagent.agentscope;

import java.time.Duration;
import java.util.Objects;

/**
 * 单次 AgentScope 运行所使用的模型与工具执行策略。
 *
 * <p>{@code modelTimeout} 与 {@code modelMaxAttempts} 会传入模型 {@code ExecutionConfig}；
 * {@code toolTimeout} 会传入工具 {@code ExecutionConfig}，并用于识别 AgentScope 2.0.0 产生的工具超时事件。
 * 工具调用固定只尝试一次，避免对可能具有外部副作用的工具进行隐式重试。</p>
 *
 * @param modelTimeout 单次模型调用的超时时间
 * @param toolTimeout 单次工具调用的超时时间
 * @param modelMaxAttempts 传递给 AgentScope 的模型调用最大尝试次数
 */
public record AgentScopeRuntimeOptions(Duration modelTimeout, Duration toolTimeout, int modelMaxAttempts) {

    /**
     * 在创建配置时校验边界，避免非法参数延迟到远程模型或工具执行阶段才失败。
     *
     * @throws NullPointerException 超时参数为空时抛出
     * @throws IllegalArgumentException 超时非正数或模型最大尝试次数不在 1 到 5 之间时抛出
     */
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
