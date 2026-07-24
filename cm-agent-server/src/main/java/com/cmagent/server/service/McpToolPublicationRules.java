package com.cmagent.server.service;

import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.ToolDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

/**
 * 集中定义工具进入 MCP 目录前必须满足的领域规则。
 */
final class McpToolPublicationRules {
    private static final Pattern MCP_TOOL_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    /**
     * McpToolPublicationRules：处理该类内部的业务逻辑或辅助计算。
     */
    private McpToolPublicationRules() {
    }
    /**
     * validateName：校验输入、状态或前置条件。
     */
    static void validateName(String name) {
        if (name == null || !MCP_TOOL_NAME.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具名称不符合 MCP 命名规则");
        }
    }
    /**
     * validateHttp：校验输入、状态或前置条件。
     */
    static void validateHttp(ToolDefinition tool, HttpToolConfig config) {
        validateName(tool.name());
        if (config == null || tool.endpoint() == null || !tool.endpoint().equals(config.urlTemplate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HTTP 工具配置不可用");
        }
    }
}
