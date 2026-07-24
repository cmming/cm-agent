package com.cmagent.core.runtime;

/**
 * ModelCredential 的核心领域类型。
 */
public final class ModelCredential {

    private final String apiKey;

    /**
     * 构造 ModelCredential 实例并校验输入参数。
     */
    public ModelCredential(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("模型 API Key 不能为空");
        }
        this.apiKey = apiKey;
    }

    /**
     * 执行 apiKey 操作。
     */
    public String apiKey() {
        return apiKey;
    }

    /**
     * 执行 toString 操作。
     */
    /**
     * 定义 toString 操作。
     */
    @Override
    public String toString() {
        return "ModelCredential[apiKey=<已脱敏>]";
    }
}
