package com.cmagent.core.tool;

import com.cmagent.core.domain.ToolDefinition;

import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

/**
 * ToolRegistry 的核心领域类型。
 */
public interface ToolRegistry {

    /**
     * 定义 register 操作。
     */
    void register(ToolDefinition definition, ToolExecutor executor);

    /**
     * 定义 find 操作。
     */
    Optional<ToolDefinition> find(UUID toolId);

    /**
     * 定义 snapshot 操作。
     */
    Optional<ToolRegistrationSnapshot> snapshot(UUID toolId);

    /**
     * 定义 execute 操作。
     */
    ToolExecutionResult execute(ToolExecutionRequest request);

    /**
     * ToolRegistrationSnapshot 的核心领域类型。
     */
    final class ToolRegistrationSnapshot {
        private final ToolDefinition definition;
        private final ToolExecutor executor;

        /**
         * 构造 ToolRegistrationSnapshot 实例并校验输入参数。
         */
        public ToolRegistrationSnapshot(ToolDefinition definition, ToolExecutor executor) {
            this.definition = Objects.requireNonNull(definition, "definition 不能为空");
            this.executor = Objects.requireNonNull(executor, "executor 不能为空");
        }

        /**
         * 执行 definition 操作。
         */
        public ToolDefinition definition() {
            return definition;
        }

        /**
         * 执行 execute 操作。
         */
        public ToolExecutionResult execute(ToolExecutionRequest request) {
            return executor.execute(request);
        }
    }
}
