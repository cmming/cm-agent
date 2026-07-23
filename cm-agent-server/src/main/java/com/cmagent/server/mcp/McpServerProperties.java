package com.cmagent.server.mcp;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.List;

@ConfigurationProperties(prefix = "cm-agent.mcp")
public class McpServerProperties implements InitializingBean {
    private boolean enabled;
    private String endpoint = "/mcp";
    private List<String> allowedOrigins = List.of();
    private List<String> allowedHosts = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = normalized(allowedOrigins);
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = normalized(allowedHosts);
    }

    @Override
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
