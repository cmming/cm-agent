package com.cmagent.server.runtime;

import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.tool.ToolRegistry;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 判断工具定义是否与当前进程中的可执行运行时配置一致。
 */
@Component
public class ToolRuntimeReadiness {
    private final ToolRegistry toolRegistry;

    public ToolRuntimeReadiness(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public boolean isReady(ToolDefinition tool, @Nullable HttpToolConfig httpConfig) {
        if (tool == null || !tool.enabled()) {
            return false;
        }
        return switch (tool.type()) {
            case HTTP -> httpConfig != null
                    && tool.tenantId().equals(httpConfig.tenantId())
                    && tool.id().equals(httpConfig.toolId())
                    && Objects.equals(tool.endpoint(), httpConfig.urlTemplate());
            case LOCAL -> toolRegistry.snapshot(tool.id())
                    .map(ToolRegistry.ToolRegistrationSnapshot::definition)
                    .filter(registered -> tool.tenantId().equals(registered.tenantId()))
                    .filter(registered -> tool.id().equals(registered.id()))
                    .filter(registered -> tool.name().equals(registered.name()))
                    .isPresent();
            default -> false;
        };
    }
}
