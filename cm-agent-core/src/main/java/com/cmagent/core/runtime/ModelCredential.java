package com.cmagent.core.runtime;

/**
 * 封装模型 API Key，并确保字符串表示始终脱敏。
 */
public final class ModelCredential {

    private final String apiKey;

    /**
     * 创建非空的模型凭据，并将密钥限制在受控对象内。
      *
      * @param apiKey 模型 API Key，禁止记录或输出
     */
    public ModelCredential(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("模型 API Key 不能为空");
        }
        this.apiKey = apiKey;
    }

    /**
     * 返回供模型客户端认证使用的 API Key；调用方不得记录该值。
     *
     * @return 模型 API Key
     */
    public String apiKey() {
        return apiKey;
    }

    /**
     * 返回不包含真实密钥的脱敏文本，避免日志意外泄露凭据。
     *
     * @return 脱敏后的凭据描述
     */
    @Override
    public String toString() {
        return "ModelCredential[apiKey=<已脱敏>]";
    }
}
