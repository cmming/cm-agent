package com.cmagent.core.runtime;

public final class ModelCredential {

    private final String apiKey;

    public ModelCredential(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("模型 API Key 不能为空");
        }
        this.apiKey = apiKey;
    }

    public String apiKey() {
        return apiKey;
    }

    @Override
    public String toString() {
        return "ModelCredential[apiKey=<已脱敏>]";
    }
}
