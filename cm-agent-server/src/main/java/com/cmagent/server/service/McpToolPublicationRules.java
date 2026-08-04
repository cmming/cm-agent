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
     * 创建 {@code McpToolPublicationRules} 实例并保存其运行所需依赖。
     */
    private McpToolPublicationRules() {
    }
    /**
     * 校验输入数据及相关业务约束。
     *
     * @param name 目标对象的名称。
     */
    static void validateName(String name) {
        if (name == null || !MCP_TOOL_NAME.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工具名称不符合 MCP 命名规则");
        }
    }
    /**
     * 校验输入数据及相关业务约束。
     *
     * @param tool 当前处理的工具定义。
     * @param config 待检查发布兼容性的动态 HTTP 工具配置
     */
    static void validateHttp(ToolDefinition tool, HttpToolConfig config) {
        validateName(tool.name());
        if (config == null || tool.endpoint() == null || !tool.endpoint().equals(config.urlTemplate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HTTP 工具配置不可用");
        }
    }
}
