package com.cmagent.core.tool;

import com.cmagent.core.domain.ToolDefinition;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于并发内存索引保存工具定义与执行器，适用于本地运行和测试。
 */
public class InMemoryToolRegistry implements ToolRegistry {

    private final ConcurrentHashMap<UUID, Registration> registrations = new ConcurrentHashMap<>();

    /**
     * 将工具定义及其执行器注册到内存索引，同一工具标识会覆盖旧注册。
     *
     * @param definition 待注册的工具定义
     * @param executor 与工具绑定的执行器
     */
    @Override
    public void register(ToolDefinition definition, ToolExecutor executor) {
        registrations.put(definition.id(), new Registration(definition, executor));
    }

    /**
     * 按工具标识查询已注册的工具定义。
     *
     * @param toolId 目标工具标识
     * @return 工具定义；未注册时为空
     */
    @Override
    public Optional<ToolDefinition> find(UUID toolId) {
        return Optional.ofNullable(registrations.get(toolId)).map(Registration::definition);
    }

    /**
     * 获取同时包含工具定义和执行器的一致注册快照。
     *
     * @param toolId 目标工具标识
     * @return 注册快照；未注册时为空
     */
    @Override
    public Optional<ToolRegistrationSnapshot> snapshot(UUID toolId) {
        return Optional.ofNullable(registrations.get(toolId))
                .map(registration -> new ToolRegistrationSnapshot(
                        registration.definition(), registration.executor()
                ));
    }

    /**
     * 查找请求对应的注册快照并执行工具，未注册时返回失败结果。
     *
     * @param request 当前工具执行请求
     * @return 工具执行结果
     */
    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        return snapshot(request.toolId())
                .map(snapshot -> snapshot.execute(request))
                .orElseGet(() -> new ToolExecutionResult("工具未注册 " + request.toolId(), false));
    }

    /**
     * 保存内存注册表中的工具定义与执行器绑定。
     */
    private record Registration(ToolDefinition definition, ToolExecutor executor) {
    }
}
