package com.cmagent.core.domain;

/**
 * 枚举运行时支持的模型提供商类型。
 */
public enum ModelProviderType {
    /** DashScope 原生协议模型，凭据与端点按 DashScope 约定解析。 */
    DASHSCOPE_NATIVE,

    /** OpenAI 兼容协议模型，可对接自建或第三方兼容网关。 */
    OPENAI_COMPATIBLE
}
