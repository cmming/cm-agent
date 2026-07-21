package com.cmagent.core.tool;

import com.cmagent.core.domain.ToolDefinition;

import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

public interface ToolRegistry {

    void register(ToolDefinition definition, ToolExecutor executor);

    Optional<ToolDefinition> find(UUID toolId);

    Optional<ToolRegistrationSnapshot> snapshot(UUID toolId);

    ToolExecutionResult execute(ToolExecutionRequest request);

    final class ToolRegistrationSnapshot {
        private final ToolDefinition definition;
        private final ToolExecutor executor;

        public ToolRegistrationSnapshot(ToolDefinition definition, ToolExecutor executor) {
            this.definition = Objects.requireNonNull(definition, "definition 不能为空");
            this.executor = Objects.requireNonNull(executor, "executor 不能为空");
        }

        public ToolDefinition definition() {
            return definition;
        }

        public ToolExecutionResult execute(ToolExecutionRequest request) {
            return executor.execute(request);
        }
    }
}
