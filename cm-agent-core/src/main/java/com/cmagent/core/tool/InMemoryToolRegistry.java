package com.cmagent.core.tool;

import com.cmagent.core.domain.ToolDefinition;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryToolRegistry implements ToolRegistry {

    private final Map<UUID, ToolDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<UUID, ToolExecutor> executors = new ConcurrentHashMap<>();

    @Override
    public void register(ToolDefinition definition, ToolExecutor executor) {
        definitions.put(definition.id(), definition);
        executors.put(definition.id(), executor);
    }

    @Override
    public Optional<ToolDefinition> find(UUID toolId) {
        return Optional.ofNullable(definitions.get(toolId));
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        ToolExecutor executor = executors.get(request.toolId());
        if (executor == null) {
            return new ToolExecutionResult("工具未注册 " + request.toolId(), false);
        }
        return executor.execute(request);
    }
}
