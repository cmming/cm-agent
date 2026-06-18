package com.cmagent.core.tool;

import com.cmagent.core.domain.ToolDefinition;

import java.util.Optional;
import java.util.UUID;

public interface ToolRegistry {

    void register(ToolDefinition definition, ToolExecutor executor);

    Optional<ToolDefinition> find(UUID toolId);

    ToolExecutionResult execute(ToolExecutionRequest request);
}
