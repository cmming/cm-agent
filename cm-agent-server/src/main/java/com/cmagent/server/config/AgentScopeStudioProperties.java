package com.cmagent.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * AgentScope Studio 本地调试集成的配置属性。
 *
 * <p>Studio Manager 在 AgentScope 2.0.2 中维护进程级静态连接和系统 Hook，因此 {@code project}
 * 与 {@code runName} 代表当前服务实例，而不是单个 CM Agent {@code runId}。多次运行的消息会在
 * 同一个 Studio Run 中展示，CM Agent 的运行记录仍以自身 {@code runId} 为准。</p>
 */
@ConfigurationProperties(prefix = "cm-agent.agentscope.studio")
public class AgentScopeStudioProperties {

    private boolean enabled;
    private String url = "http://localhost:8000";
    private String project = "cm-agent";
    private String runName = "cm-agent";

    /**
     * @return 是否向 Studio 转发 AgentScope 消息。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param enabled 是否向 Studio 转发 AgentScope 消息。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return Studio HTTP 与 WebSocket 服务地址。
     */
    public String getUrl() {
        return url;
    }

    /**
     * @param url Studio HTTP 与 WebSocket 服务地址。
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * @return Studio 中归集当前服务实例的项目名。
     */
    public String getProject() {
        return project;
    }

    /**
     * @param project Studio 中归集当前服务实例的项目名。
     */
    public void setProject(String project) {
        this.project = project;
    }

    /**
     * @return Studio 中标识当前服务实例的 Run 名称。
     */
    public String getRunName() {
        return runName;
    }

    /**
     * @param runName Studio 中标识当前服务实例的 Run 名称。
     */
    public void setRunName(String runName) {
        this.runName = runName;
    }

    /**
     * 校验启用 Studio 时必须提供可用且不包含敏感信息的连接标识。
     *
     * @throws IllegalStateException 地址、项目名或运行名称不符合调试连接要求时抛出
     */
    public void validate() {
        if (!isHttpUrl(url)) {
            throw new IllegalStateException("AgentScope Studio 地址必须是 HTTP(S) 绝对地址且不能包含用户信息、查询串或片段");
        }
        if (isBlank(project)) {
            throw new IllegalStateException("AgentScope Studio 项目名不能为空");
        }
        if (isBlank(runName)) {
            throw new IllegalStateException("AgentScope Studio Run 名称不能为空");
        }
    }

    private static boolean isHttpUrl(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute() && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && !isBlank(uri.getHost()) && uri.getUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
