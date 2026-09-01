package com.cmagent.core.tool;

import com.cmagent.core.domain.ToolDefinition;

import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

/**
 * 管理工具定义与执行器注册，并提供按标识查询和执行能力。
 *
 * <p>注册表是 Server 工具治理的本地视图：实现需要保证“查询定义”与“执行时拿到的执行器”
 * 来自同一次注册，避免查询和执行之间定义或执行器被替换造成不一致。</p>
 */
public interface ToolRegistry {

    /**
     * 注册工具定义及其执行器。
     *
     * @param definition 待注册的工具领域定义
     * @param executor 与该工具绑定的执行器，负责工具的真实执行逻辑
     */
    void register(ToolDefinition definition, ToolExecutor executor);

    /**
     * 按工具标识查询已注册的工具定义。
     *
     * @param toolId 目标工具标识
     * @return 工具定义；未注册时返回空
     */
    Optional<ToolDefinition> find(UUID toolId);

    /**
     * 按工具标识获取定义与执行器的一致注册快照。
     *
     * @param toolId 目标工具标识
     * @return 注册快照；未注册时返回空
     */
    Optional<ToolRegistrationSnapshot> snapshot(UUID toolId);

    /**
     * 执行工具请求并返回统一结果。
     *
     * <p>请求上下文的完整性校验由 {@link ToolExecutionRequest} 构造期保证；实现只需处理
     * “未注册”与“执行失败”两类普通失败，均以失败结果返回而不得抛出受控异常。</p>
     *
     * @param request 当前工具执行请求
     * @return 工具执行结果；工具未注册时返回失败结果而非抛异常
     */
    ToolExecutionResult execute(ToolExecutionRequest request);

    /**
     * 保存某一时刻的工具定义与执行器绑定，避免查询和执行之间发生不一致。
     */
    final class ToolRegistrationSnapshot {
        private final ToolDefinition definition;
        private final ToolExecutor executor;

        /**
         * 创建快照，工具定义与执行器均不可缺省。
         *
         * @param definition 注册时的工具领域定义
         * @param executor 注册时与工具绑定的执行器
         */
        public ToolRegistrationSnapshot(ToolDefinition definition, ToolExecutor executor) {
            this.definition = Objects.requireNonNull(definition, "definition 不能为空");
            this.executor = Objects.requireNonNull(executor, "executor 不能为空");
        }

        /**
         * 返回注册快照中的工具定义。
         *
         * @return 注册时保存的工具定义
         */
        public ToolDefinition definition() {
            return definition;
        }

        /**
         * 使用快照中绑定的执行器执行工具请求。
         *
         * @param request 当前工具执行请求
         * @return 工具执行结果
         */
        public ToolExecutionResult execute(ToolExecutionRequest request) {
            return executor.execute(request);
        }
    }
}
