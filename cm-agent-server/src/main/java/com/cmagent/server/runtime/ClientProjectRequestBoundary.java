package com.cmagent.server.runtime;

import com.cmagent.server.config.ClientProjectProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Set;

/**
 * 客户端项目浏览器入口的精确来源检查，与设备签名任务通道相互独立。
 *
 * <p>设备签名只证明任务领取者持有已配对密钥，不能证明浏览器请求同源。此边界要求
 * 固定 Host、Origin、Fetch Metadata 和专用行动头，并拒绝代理转发头推断来源。</p>
 */
public final class ClientProjectRequestBoundary {
    private final Set<String> origins;
    private final Set<String> hosts;
    private final boolean allowNonLoopback;

    public ClientProjectRequestBoundary(ClientProjectProperties properties) {
        properties.validate();
        origins = Set.copyOf(properties.getAllowedOrigins());
        hosts = origins.stream().map(value -> URI.create(value).getRawAuthority())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        allowNonLoopback = properties.isAllowNonLoopback();
    }

    /**
     * @param request 原始 Servlet 请求，部署必须关闭不可信转发头重写
     * @return 是否允许进入项目认证和任务登记
     */
    public boolean permits(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        return (allowNonLoopback || loopbackAddress(request.getRemoteAddr())
                    && loopbackAddress(request.getLocalAddr()))
                && request.getHeader("Forwarded") == null
                && request.getHeader("X-Forwarded-For") == null
                && request.getHeader("X-Forwarded-Host") == null
                && request.getHeader("X-Real-IP") == null
                && hosts.contains(request.getHeader("Host"))
                && "1".equals(request.getHeader("X-CM-Agent-Client-Project"))
                && (origin == null || origins.contains(origin))
                && (fetchSite == null || "same-origin".equals(fetchSite) || "none".equals(fetchSite));
    }

    private static boolean loopbackAddress(String address) {
        return "127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
    }
}
