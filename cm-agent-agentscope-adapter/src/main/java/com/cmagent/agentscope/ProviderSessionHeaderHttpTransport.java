package com.cmagent.agentscope;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * 为需要会话路由的模型 Provider 追加受控请求头的传输包装器。
 *
 * <p>AgentScope 2.0.0 的 {@code OpenAIChatModel.Builder} 没有自定义请求头入口，因而仅在
 * {@code opencode.ai} 的 OpenCode Go 协议路径包装其共享默认传输。会话值来自服务端 Run ID，
 * 不接受模型配置、提示词或浏览器传入的任意 Header，避免把通用模型配置变成任意 Header 注入通道。</p>
 */
final class ProviderSessionHeaderHttpTransport implements HttpTransport {
    static final String OPENCODE_HOST = "opencode.ai";
    static final String OPENCODE_SESSION_HEADER = "x-opencode-session";

    private final HttpTransport delegate;
    private final String sessionId;

    /**
     * 创建包装共享传输的 OpenCode 会话头适配器。
     *
     * @param delegate AgentScope 管理的共享 HTTP 传输
     * @param sessionId 当前服务端运行标识，不能为空
     */
    ProviderSessionHeaderHttpTransport(HttpTransport delegate, String sessionId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("OpenCode 会话标识不能为空");
        }
        this.sessionId = sessionId;
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        return delegate.execute(withSessionHeader(request));
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        return delegate.stream(withSessionHeader(request));
    }

    @Override
    public void close() {
        // 底层来自 HttpTransportFactory 的进程级共享实例，只能由框架统一关闭，不能被单次 Run 关闭。
    }

    /**
     * 判断基础地址是否精确指向 OpenCode Go 的公开域名。
     *
     * @param baseUrl 已通过模型配置校验的基础地址
     * @return 目标主机为 OpenCode 时返回 {@code true}
     */
    static boolean isOpenCodeBaseUrl(String baseUrl) {
        try {
            URI uri = new URI(baseUrl);
            return OPENCODE_HOST.equalsIgnoreCase(uri.getHost());
        } catch (URISyntaxException exception) {
            // 模型配置在进入工厂前已经校验；此处保守地不为无法解析的地址追加 Provider 专用头。
            return false;
        }
    }

    private HttpRequest withSessionHeader(HttpRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        return HttpRequest.builder()
                .url(request.getUrl())
                .method(request.getMethod())
                .headers(request.getHeaders())
                .header(OPENCODE_SESSION_HEADER, sessionId)
                .body(request.getBody())
                .build();
    }
}
