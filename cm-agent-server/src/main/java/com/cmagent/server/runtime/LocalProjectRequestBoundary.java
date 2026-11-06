package com.cmagent.server.runtime;

import com.cmagent.server.config.LocalProjectProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Set;

/**
 * 本机目录授权不能通过代理或DNS重绑定从其他网页触发；不信任Forwarded/X-Forwarded-*。
 *
 * <p>默认要求连接双方均为回环地址。只有显式启用 allow-non-loopback 后，地址条件才放宽；
 * Host、Origin、自定义行动头和 Fetch Metadata 检查仍必须全部满足。</p>
 */
public final class LocalProjectRequestBoundary {
    /** 预先校验的精确源；不能从请求Host动态扩展。 */
    private final Set<String> origins;
    /** 与上述源配对的精确Host（含端口）。 */
    private final Set<String> hosts;
    /** 显式部署选择；只影响地址来源条件，不关闭同源与浏览器元数据检查。 */
    private final boolean allowNonLoopback;

    /** @param properties 来自部署配置、已校验的本机项目策略 */
    public LocalProjectRequestBoundary(LocalProjectProperties properties) {
        properties.validate();
        origins = Set.copyOf(properties.getAllowedOrigins());
        hosts = origins.stream().map(value -> URI.create(value).getRawAuthority()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        allowNonLoopback = properties.isAllowNonLoopback();
    }

    /**
     * 必须在身份认证后、任何项目查询/原生窗口之前执行；自定义头使浏览器跨源请求必须预检。
     * @param request 原始Servlet连接信息，部署时禁止启用转发头重写
     * @return 是否满足显式本机操作约束，拒绝不泄露允许源
     */
    public boolean permits(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        return (allowNonLoopback || (loopbackAddress(request.getRemoteAddr()) && loopbackAddress(request.getLocalAddr())))
                && request.getHeader("Forwarded") == null && request.getHeader("X-Forwarded-For") == null
                && request.getHeader("X-Forwarded-Host") == null && request.getHeader("X-Real-IP") == null
                && hosts.contains(request.getHeader("Host"))
                && "1".equals(request.getHeader("X-CM-Agent-Local-Project"))
                && (origin == null || origins.contains(origin))
                && (fetchSite == null || "same-origin".equals(fetchSite) || "none".equals(fetchSite));
    }

    private static boolean loopbackAddress(String address) {
        return "127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
    }
}
