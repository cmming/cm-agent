package com.cmagent.server.service;

import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.ToolDefinition;

/**
 * 面向控制台的工具摘要，不包含请求头密钥等敏感配置。
 */
public record ToolSummary(
        ToolDefinition tool,
        HttpToolConfig httpConfig,
        boolean mcpPublished
) {
}
