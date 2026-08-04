package com.cmagent.server.runtime;

import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolRegistry;
import com.cmagent.server.runtime.http.DynamicHttpToolExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Service
/** 在真正执行工具前落实工具状态、租户和权限治理，并记录调用结果。 */
public class GovernedToolExecutionService {
    private static final String TOOL_UNAVAILABLE = "工具不可用";

    private final HttpToolConfigRepository configs;
    private final DynamicHttpToolExecutor http;
    private final ToolRegistry registry;
    /**
     * 创建 {@code GovernedToolExecutionService} 实例并保存其运行所需依赖。
     *
     * @param configs 动态 HTTP 工具配置仓储
     * @param http 动态 HTTP 工具执行器
     * @param registry 本地工具执行器注册表。
     */
    public GovernedToolExecutionService(
            HttpToolConfigRepository configs,
            DynamicHttpToolExecutor http,
            ToolRegistry registry
    ) {
        this.configs = Objects.requireNonNull(configs, "configs 不能为空");
        this.http = Objects.requireNonNull(http, "http 不能为空");
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
    }

    /**
     * 执行已完成治理准备的工具请求。
     *
     * @param tool    工具定义
     * @param request 已校验的工具调用请求
     * @return 工具执行结果
     * @throws RuntimeException 工具执行器不存在或执行失败时抛出
     */
    public ToolExecutionResult execute(ToolDefinition tool, ToolExecutionRequest request) {
        return prepare(tool, request).execute();
    }

    /**
     * 在工具注册状态满足要求后执行工具。
     *
     * @param tool            工具定义
     * @param request         工具调用请求
     * @param beforeExecution 工具真正执行前的回调，通常用于写入调用开始审计
     * @return 工具执行结果
     * @throws ToolPreparationDataAccessException 准备工具时访问数据失败
     * @throws RuntimeException                   工具不可用或执行失败时抛出
     */
    public ToolExecutionResult executeWhenReady(
            ToolDefinition tool,
            ToolExecutionRequest request,
            Runnable beforeExecution
    ) {
        Objects.requireNonNull(beforeExecution, "beforeExecution 不能为空");
        PreparedToolExecution prepared;
        try {
            prepared = prepare(tool, request);
        } catch (DataAccessException dataAccessFailure) {
            throw new ToolPreparationDataAccessException(dataAccessFailure);
        }
        if (!prepared.ready()) {
            return prepared.execute();
        }
        beforeExecution.run();
        return prepared.execute();
    }
    /**
     * 执行授权、可见性和运行时一致性检查，生成可执行工具上下文。
     *
     * @param tool 当前处理的工具定义。
     * @param request 包含调用来源、租户上下文和输入 JSON 的工具执行请求
     */
    PreparedToolExecution prepare(ToolDefinition tool, ToolExecutionRequest request) {
        Objects.requireNonNull(tool, "tool 不能为空");
        Objects.requireNonNull(request, "request 不能为空");
        if (!tool.enabled()
                || !tool.tenantId().equals(request.tenantId())
                || !tool.id().equals(request.toolId())) {
            return PreparedToolExecution.unavailable();
        }
        if (tool.type() == ToolType.HTTP) {
            return configs.findByTenantAndToolId(tool.tenantId(), tool.id())
                    .filter(config -> isMatchingHttpConfiguration(tool, config))
                    .map(config -> PreparedToolExecution.ready(() -> http.execute(tool, config, request)))
                    .orElseGet(PreparedToolExecution::unavailable);
        }
        if (tool.type() == ToolType.LOCAL) {
            ToolRegistry.ToolRegistrationSnapshot snapshot = registry.snapshot(tool.id()).orElse(null);
            ToolDefinition registered = snapshot == null ? null : snapshot.definition();
            if (!isSameRegistration(tool, registered)) {
                return PreparedToolExecution.unavailable();
            }
            return PreparedToolExecution.ready(() -> snapshot.execute(request));
        }
        return PreparedToolExecution.unavailable();
    }

    /**
     * 判断当前 HTTP 配置是否与准备阶段快照一致。
     *
     * @param tool 当前处理的工具定义。
     * @param config 待核对端点一致性的动态 HTTP 工具配置
     */
    private boolean isMatchingHttpConfiguration(ToolDefinition tool, HttpToolConfig config) {
        return tool.endpoint() != null && tool.endpoint().equals(config.urlTemplate());
    }

    /**
     * 判断两个本地工具注册是否指向同一执行器。
     *
     * @param tool 当前处理的工具定义。
     * @param registered 注册表中与目标标识对应的工具定义
     */
    private boolean isSameRegistration(ToolDefinition tool, ToolDefinition registered) {
        return registered != null
                && tool.tenantId().equals(registered.tenantId())
                && tool.id().equals(registered.id())
                && tool.name().equals(registered.name());
    }

    /**
     * 创建 {@code PreparedToolExecution} 实例并保存其运行所需依赖。
     */
    static final class PreparedToolExecution {
        private final Supplier<ToolExecutionResult> execution;
        private final ToolExecutionResult unavailableResult;
        private final AtomicBoolean consumed;

        /**
         * 创建 {@code PreparedToolExecution} 实例并保存其运行所需依赖。
         *
     * @param execution 延迟执行已通过治理校验的工具操作
     * @param unavailableResult 治理校验未通过时返回的安全失败结果
         */
        private PreparedToolExecution(Supplier<ToolExecutionResult> execution, ToolExecutionResult unavailableResult) {
            this.execution = execution;
            this.unavailableResult = unavailableResult;
            this.consumed = execution == null ? null : new AtomicBoolean();
        }
        /**
         * 创建准备完成且可以执行的工具上下文。
         *
     * @param execution 延迟执行已通过治理校验的工具操作
         */
        static PreparedToolExecution ready(Supplier<ToolExecutionResult> execution) {
            return new PreparedToolExecution(Objects.requireNonNull(execution, "execution 不能为空"), null);
        }
        /**
         * 创建不可执行的准备结果，供治理校验失败时安全返回。
         */
        static PreparedToolExecution unavailable() {
            return new PreparedToolExecution(null, ToolExecutionResult.failed(TOOL_UNAVAILABLE, null));
        }
        /**
         * 创建准备完成且可以执行的工具上下文。
         */
        boolean ready() {
            return execution != null;
        }
        /**
         * 执行已经完成治理校验的工具，并统一处理输出和失败。
         */
        ToolExecutionResult execute() {
            if (!ready()) {
                return unavailableResult;
            }
            if (!consumed.compareAndSet(false, true)) {
                return ToolExecutionResult.failed(TOOL_UNAVAILABLE, null);
            }
            return execution.get();
        }
    }
}
