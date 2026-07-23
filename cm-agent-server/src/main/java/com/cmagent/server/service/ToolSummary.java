package com.cmagent.server.service;

import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.ToolDefinition;

public record ToolSummary(
        ToolDefinition tool,
        HttpToolConfig httpConfig,
        boolean mcpPublished
) {
}
