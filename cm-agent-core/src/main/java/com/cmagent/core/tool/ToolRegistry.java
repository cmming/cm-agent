package com.cmagent.core.tool;

import com.cmagent.core.domain.ToolDefinition;

import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

/**
 * 管理工具定义与执行器注册，并提供按标识查询和执行能力。
 */
public interface ToolRegistry {

    /**
     * 注册工具定义及其执行器。
      *
      * @param definition 当前领域定义
      * @param executor AgentScope 或异步任务执行器
     */
    void register(ToolDefinition definition, ToolExecutor executor);

    /**
     * 按工具标识查询已注册的工具定义。
      *
      * @param toolId 目标工具标识
     */
    Optional<ToolDefinition> find(UUID toolId);

    /**
     * 按工具标识获取定义与执行器的一致注册快照。
      *
      * @param toolId 目标工具标识
     */
    Optional<ToolRegistrationSnapshot> snapshot(UUID toolId);

    /**
     * 执行工具请求并返回统一结果。
      *
      * @param request 当前运行或工具调用请求
     */
    ToolExecutionResult execute(ToolExecutionRequest request);

    /**
     * 保存某一时刻的工具定义与执行器绑定，避免查询和执行之间发生不一致。
     */
    final class ToolRegistrationSnapshot {
        private final ToolDefinition definition;
        private final ToolExecutor executor;

        /**
     * 创建不可缺少工具定义或执行器的注册快照。
          *
          * @param definition 当前领域定义
          * @param executor AgentScope 或异步任务执行器
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
