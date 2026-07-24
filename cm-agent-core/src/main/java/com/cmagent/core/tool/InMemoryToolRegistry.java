package com.cmagent.core.tool;

import com.cmagent.core.domain.ToolDefinition;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryToolRegistry 的核心领域类型。
 */
public class InMemoryToolRegistry implements ToolRegistry {

    private final ConcurrentHashMap<UUID, Registration> registrations = new ConcurrentHashMap<>();

    /**
     * 执行 register 操作。
     */
    /**
     * 定义 register 操作。
     */
    @Override
    public void register(ToolDefinition definition, ToolExecutor executor) {
        registrations.put(definition.id(), new Registration(definition, executor));
    }

    /**
     * 执行 find 操作。
     */
    /**
     * 定义 find 操作。
     */
    @Override
    public Optional<ToolDefinition> find(UUID toolId) {
        return Optional.ofNullable(registrations.get(toolId)).map(Registration::definition);
    }

    /**
     * 执行 snapshot 操作。
     */
    /**
     * 定义 snapshot 操作。
     */
    @Override
    public Optional<ToolRegistrationSnapshot> snapshot(UUID toolId) {
        return Optional.ofNullable(registrations.get(toolId))
                .map(registration -> new ToolRegistrationSnapshot(
                        registration.definition(), registration.executor()
                ));
    }

    /**
     * 执行 execute 操作。
     */
    /**
     * 定义 execute 操作。
     */
    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        return snapshot(request.toolId())
                .map(snapshot -> snapshot.execute(request))
                .orElseGet(() -> new ToolExecutionResult("工具未注册 " + request.toolId(), false));
    }

    /**
     * Registration 的核心领域类型。
     */
    private record Registration(ToolDefinition definition, ToolExecutor executor) {
    }
}
