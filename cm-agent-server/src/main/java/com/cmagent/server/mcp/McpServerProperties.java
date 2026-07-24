package com.cmagent.server.mcp;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.List;

@ConfigurationProperties(prefix = "cm-agent.mcp")
/** MCP 端点配置属性，并在启动阶段校验路径和会话参数。 */
public class McpServerProperties implements InitializingBean {
    private boolean enabled;
    private String endpoint = "/mcp";
    private List<String> allowedOrigins = List.of();
    private List<String> allowedHosts = List.of();

    /**
     * @return 是否启用 MCP Streamable HTTP 端点。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param enabled 是否启用 MCP Streamable HTTP 端点。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return MCP HTTP 端点路径。
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * @param endpoint MCP HTTP 端点路径，写入时会去除首尾空白。
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
    }

    /**
     * @return MCP 请求允许的 Origin 白名单。
     */
    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    /**
     * @param allowedOrigins MCP 请求允许的 Origin 白名单，将被规范化并复制。
     */
    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = normalized(allowedOrigins);
    }

    /**
     * @return MCP 请求允许的 Host 白名单。
     */
    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    /**
     * @param allowedHosts MCP 请求允许的 Host 白名单，将被规范化并复制。
     */
    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = normalized(allowedHosts);
    }

    @Override
    /**
     * 在 Spring 完成属性绑定后校验 MCP 端点和白名单配置。
     *
     * @throws IllegalStateException MCP 已启用但端点或白名单不合法时抛出
     */
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        if (!endpoint.startsWith("/") || endpoint.length() < 2 || endpoint.endsWith("/")
                || endpoint.contains("?") || endpoint.contains("#") || endpoint.contains("*")) {
            throw new IllegalStateException("cm-agent.mcp.endpoint 必须是单一绝对路径");
        }
        if (allowedOrigins.isEmpty()) {
            throw new IllegalStateException("启用 MCP 时 cm-agent.mcp.allowedOrigins 不能为空");
        }
        if (allowedHosts.isEmpty()) {
            throw new IllegalStateException("启用 MCP 时 cm-agent.mcp.allowedHosts 不能为空");
        }
    }

    private static List<String> normalized(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String candidate = value == null ? "" : value.trim();
            if (candidate.isEmpty() || candidate.contains(",") || candidate.contains("\r") || candidate.contains("\n")) {
                throw new IllegalArgumentException("MCP 白名单项不能为空或包含分隔符");
            }
            normalized.add(candidate);
        }
        return List.copyOf(normalized);
    }
}
