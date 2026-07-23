package com.cmagent.core.tool;

import com.cmagent.core.domain.ToolDefinition;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryToolRegistry implements ToolRegistry {

    private final ConcurrentHashMap<UUID, Registration> registrations = new ConcurrentHashMap<>();

    @Override
    public void register(ToolDefinition definition, ToolExecutor executor) {
        registrations.put(definition.id(), new Registration(definition, executor));
    }

    @Override
    public Optional<ToolDefinition> find(UUID toolId) {
        return Optional.ofNullable(registrations.get(toolId)).map(Registration::definition);
    }

    @Override
    public Optional<ToolRegistrationSnapshot> snapshot(UUID toolId) {
        return Optional.ofNullable(registrations.get(toolId))
                .map(registration -> new ToolRegistrationSnapshot(
                        registration.definition(), registration.executor()
                ));
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        return snapshot(request.toolId())
                .map(snapshot -> snapshot.execute(request))
                .orElseGet(() -> new ToolExecutionResult("工具未注册 " + request.toolId(), false));
    }

    private record Registration(ToolDefinition definition, ToolExecutor executor) {
    }
}
