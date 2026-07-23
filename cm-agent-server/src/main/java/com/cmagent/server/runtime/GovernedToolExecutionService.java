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
public class GovernedToolExecutionService {
    private static final String TOOL_UNAVAILABLE = "工具不可用";

    private final HttpToolConfigRepository configs;
    private final DynamicHttpToolExecutor http;
    private final ToolRegistry registry;

    public GovernedToolExecutionService(
            HttpToolConfigRepository configs,
            DynamicHttpToolExecutor http,
            ToolRegistry registry
    ) {
        this.configs = Objects.requireNonNull(configs, "configs 不能为空");
        this.http = Objects.requireNonNull(http, "http 不能为空");
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
    }

    public ToolExecutionResult execute(ToolDefinition tool, ToolExecutionRequest request) {
        return prepare(tool, request).execute();
    }

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

    private boolean isMatchingHttpConfiguration(ToolDefinition tool, HttpToolConfig config) {
        return tool.endpoint() != null && tool.endpoint().equals(config.urlTemplate());
    }

    private boolean isSameRegistration(ToolDefinition tool, ToolDefinition registered) {
        return registered != null
                && tool.tenantId().equals(registered.tenantId())
                && tool.id().equals(registered.id())
                && tool.name().equals(registered.name());
    }

    static final class PreparedToolExecution {
        private final Supplier<ToolExecutionResult> execution;
        private final ToolExecutionResult unavailableResult;
        private final AtomicBoolean consumed;

        private PreparedToolExecution(Supplier<ToolExecutionResult> execution, ToolExecutionResult unavailableResult) {
            this.execution = execution;
            this.unavailableResult = unavailableResult;
            this.consumed = execution == null ? null : new AtomicBoolean();
        }

        static PreparedToolExecution ready(Supplier<ToolExecutionResult> execution) {
            return new PreparedToolExecution(Objects.requireNonNull(execution, "execution 不能为空"), null);
        }

        static PreparedToolExecution unavailable() {
            return new PreparedToolExecution(null, ToolExecutionResult.failed(TOOL_UNAVAILABLE, null));
        }

        boolean ready() {
            return execution != null;
        }

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
