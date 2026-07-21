package com.cmagent.server.runtime;

import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.tool.ToolExecutionRequest;
import com.cmagent.core.tool.ToolExecutionResult;
import com.cmagent.core.tool.ToolRegistry;
import com.cmagent.server.runtime.http.DynamicHttpToolExecutor;
import org.springframework.stereotype.Service;

import java.util.Objects;

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
        Objects.requireNonNull(tool, "tool 不能为空");
        Objects.requireNonNull(request, "request 不能为空");
        if (!tool.enabled()
                || !tool.tenantId().equals(request.tenantId())
                || !tool.id().equals(request.toolId())) {
            return unavailable();
        }
        if (tool.type() == ToolType.HTTP) {
            return configs.findByTenantAndToolId(tool.tenantId(), tool.id())
                    .filter(config -> isMatchingHttpConfiguration(tool, config))
                    .map(config -> http.execute(tool, config, request))
                    .orElseGet(this::unavailable);
        }
        if (tool.type() == ToolType.LOCAL) {
            ToolDefinition registered = registry.find(tool.id()).orElse(null);
            if (!isSameRegistration(tool, registered)) {
                return unavailable();
            }
            return registry.execute(request);
        }
        return unavailable();
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

    private ToolExecutionResult unavailable() {
        return ToolExecutionResult.failed(TOOL_UNAVAILABLE, null);
    }
}
