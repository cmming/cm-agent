package com.cmagent.server.config;

import com.cmagent.server.runtime.http.HttpToolProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

/**
 * 模型目录发现的独立出站网络限制。
 *
 * <p>该配置复用 {@link HttpToolProperties} 的协议、主机、DNS 和响应大小校验规则，
 * 但不继承或复用动态 HTTP 工具的开关、Bean 身份或 Secret。模型发现会携带模型 API Key，因而默认只允许
 * 官方 OpenAI 与 DashScope 主机；接入自建网关前必须显式加入 {@code allowed-hosts}。</p>
 */
@ConfigurationProperties(prefix = "cm-agent.model-catalog-discovery")
public class ModelCatalogDiscoveryProperties {
    private static final int ABSOLUTE_MAX_MODELS = 500;
    private static final int ABSOLUTE_MAX_RESPONSE_BYTES = 262_144;

    private boolean allowHttp;
    private Set<String> allowedHosts = Set.of("api.openai.com", "dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com");
    private int maxResponseBytes = 131_072;
    private Duration timeout = Duration.ofSeconds(5);
    private int maxModels = 200;

    /**
     * @return 是否允许模型目录使用明文 HTTP；默认仅允许 HTTPS
     */
    public boolean isAllowHttp() {
        return allowHttp;
    }

    /**
     * @param allowHttp 是否允许明文 HTTP
     */
    public void setAllowHttp(boolean allowHttp) {
        this.allowHttp = allowHttp;
    }

    /**
     * @return 允许携带模型凭据访问的供应商主机白名单
     */
    public Set<String> getAllowedHosts() {
        return allowedHosts;
    }

    /**
     * @param allowedHosts 供应商主机白名单，将被复制为不可变集合
     */
    public void setAllowedHosts(Set<String> allowedHosts) {
        this.allowedHosts = Set.copyOf(allowedHosts == null ? Set.of() : allowedHosts);
    }

    /**
     * @return 模型目录响应的最大字节数
     */
    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    /**
     * @param maxResponseBytes 响应最大字节数，必须在 1 到 262144 之间
     */
    public void setMaxResponseBytes(int maxResponseBytes) {
        if (maxResponseBytes < 1 || maxResponseBytes > ABSOLUTE_MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("模型目录发现 maxResponseBytes 必须在 1 到 262144 之间");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * @return 单次模型目录请求的总超时
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * 设置单次模型目录请求的总超时。
     *
     * <p>目录发现只用于显式表单操作，不应占用模型运行的长超时；同时沿用 HTTP 工具的
     * 100 毫秒至 30 秒绝对边界，避免配置无限等待。</p>
     *
     * @param timeout 请求超时，必须在 100 毫秒到 30 秒之间
     */
    public void setTimeout(Duration timeout) {
        if (timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("模型目录发现 timeout 必须在 100ms 到 30s 之间");
        }
        this.timeout = timeout;
    }

    /**
     * @return 单次响应最多返回并展示的模型数量
     */
    public int getMaxModels() {
        return maxModels;
    }

    /**
     * 设置单次响应最多返回并展示的模型数量。
     *
     * @param maxModels 最大数量，必须在 1 到 500 之间
     */
    public void setMaxModels(int maxModels) {
        if (maxModels < 1 || maxModels > ABSOLUTE_MAX_MODELS) {
            throw new IllegalArgumentException("模型目录发现 maxModels 必须在 1 到 500 之间");
        }
        this.maxModels = maxModels;
    }

    /**
     * 将独立配置转换为既有 URL 策略所需的只读安全参数。
     *
     * <p>不把本对象继承为 {@link HttpToolProperties}，避免 Spring 在动态 HTTP 工具注入时把模型
     * 目录配置识别为第二个候选 Bean；两种出站能力共享算法而不共享生命周期和开关。</p>
     *
     * @return 仅供 {@code HttpToolUrlPolicy} 使用的安全属性副本
     */
    public HttpToolProperties toUrlPolicyProperties() {
        HttpToolProperties properties = new HttpToolProperties();
        properties.setAllowHttp(allowHttp);
        properties.setAllowedHosts(allowedHosts);
        properties.setMaxResponseBytes(maxResponseBytes);
        properties.setMaxRedirects(0);
        return properties;
    }
}
