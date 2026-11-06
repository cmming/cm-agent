package com.cmagent.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** 本机项目独立部署策略；默认关闭，不接受浏览器上传宿主路径或临时生成节点身份。 */
@ConfigurationProperties("cm-agent.local-projects")
public final class LocalProjectProperties {
    /** 必须显式开启，关闭时旧工作区行为不变。 */
    private boolean enabled;
    /** 持久节点身份；更换节点不能接管旧项目根或在途审批。 */
    private String nodeId = "";
    /** 私有持久目录，由部署者预建并限制ACL，不可作为项目根。 */
    private String stateDirectory = "";
    /** 是否显式允许非回环来源访问本机项目能力；默认关闭。 */
    private boolean allowNonLoopback;
    /** 精确同源地址，不接受通配或转发头推断；非回环主机仅在 allowNonLoopback 开启时允许。 */
    private List<String> allowedOrigins = List.of();
    /** 原生选择授权固定有效期，不随轮询续期。 */
    private Duration selectionTtl = Duration.ofMinutes(2);
    /** 本机bootstrap主体显式追加的项目权限，默认空，不自动扩大已有角色或通用工具授权。 */
    private List<String> bootstrapPermissions = List.of();
    /** 只允许本功能四项明细，不接受通配符或任意平台权限扩展。 */
    private static final Set<String> BOOTSTRAP_PERMISSION_CODES=Set.of("project:read","project:write","project:execute","project:manage");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String value) { nodeId = value; }
    public String getStateDirectory() { return stateDirectory; }
    public void setStateDirectory(String value) { stateDirectory = value; }
    public boolean isAllowNonLoopback() { return allowNonLoopback; }
    public void setAllowNonLoopback(boolean value) { allowNonLoopback = value; }
    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> value) { allowedOrigins = value == null ? List.of() : List.copyOf(value); }
    public Duration getSelectionTtl() { return selectionTtl; }
    public void setSelectionTtl(Duration value) { selectionTtl = value; }
    public List<String> getBootstrapPermissions() { return bootstrapPermissions; }
    public void setBootstrapPermissions(List<String> value) { bootstrapPermissions=value==null?List.of():List.copyOf(value); }

    /** 登录和启动共同复核固定白名单；不能由JWT请求、客户端身份或其他权限名扩展授权。 */
    public void validateBootstrapPermissions() {
        if(bootstrapPermissions.size()>4||bootstrapPermissions.stream().distinct().count()!=bootstrapPermissions.size()
                ||!BOOTSTRAP_PERMISSION_CODES.containsAll(bootstrapPermissions))
            throw new IllegalStateException("本地项目bootstrap权限只允许四项明确项目权限且不能重复");
    }

    /** 启用前必须有明确节点、私有持久卷和精确回环源；失败消息不含路径。 */
    public void validate() {
        if (!enabled) return;
        validateBootstrapPermissions();
        try {
            if (nodeId == null || !nodeId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")
                    || stateDirectory == null || !Path.of(stateDirectory).isAbsolute()
                    || Path.of(stateDirectory).getParent() == null || allowedOrigins.isEmpty()
                    || allowedOrigins.size() > 8 || selectionTtl == null || selectionTtl.isNegative()
                    || selectionTtl.isZero() || selectionTtl.compareTo(Duration.ofMinutes(5)) > 0)
                throw new IllegalArgumentException();
            for (String origin : allowedOrigins) {
                URI uri = URI.create(origin);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                        || uri.getHost() == null || (!allowNonLoopback && !loopbackHost(uri.getHost()))
                        || wildcardHost(uri.getHost()) || uri.getRawUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                        || !origin.equals(uri.getScheme() + "://" + uri.getRawAuthority()))
                    throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("本地项目节点、私有目录、选择期限或来源配置无效");
        }
    }

    /** 只接受字面回环主机，不通过可变DNS把外部名称解释成本机。 */
    public static boolean loopbackHost(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host)
                || "[::1]".equals(host) || "::1".equals(host);
    }
    /** 通配监听地址不是可访问的浏览器来源，不能出现在 allowedOrigins 中。 */
    private static boolean wildcardHost(String host) {
        return "0.0.0.0".equals(host) || "::".equals(host) || "[::]".equals(host);
    }
    /** 配置对象可能被框架打印，不能包含宿主私有路径。 */
    @Override public String toString() { return "LocalProjectProperties[enabled=" + enabled + ", 受保护的本机配置]"; }
}
