package com.cmagent.agentscope;

import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelFailureSummaryTest {
    @Test
    void DashScope独立HTTP异常也保留实际状态和错误码() {
        var failure = new ModelException("包装层消息", new io.agentscope.extensions.model.dashscope.DashScopeHttpClient.DashScopeHttpException(
                "不返回异常消息", 401, "{\"message\":\"凭据已失效\",\"code\":\"InvalidApiKey\",\"request_id\":\"不返回的额外字段\"}"));
        assertThat(ModelFailureSummary.describe(failure, "test-key"))
                .contains("HTTP 401", "凭据已失效", "InvalidApiKey").doesNotContain("不返回", "包装层消息");
    }
    @Test
    void 保留真实状态和结构化说明而不使用异常消息() {
        for (int status : new int[]{400, 401, 403, 404, 429, 500, 503}) {
            var failure = new ModelException("不应返回的包装层消息", new HttpTransportException(
                    "不应返回的传输消息", status, "{\"error\":{\"message\":\"模型不存在\",\"code\":\"model_not_found\"},\"request\":{\"secret\":\"outside-message\"}}"));
            assertThat(ModelFailureSummary.describe(failure, "test-key"))
                    .contains("HTTP " + status, "模型不存在", "model_not_found")
                    .doesNotContain("包装层", "传输消息", "outside-message", "request");
        }
    }

    @Test
    void 读取可选Provider公开响应方法并清理凭据地址路径和堆栈() {
        var failure = new OpenAIException("不返回异常消息", 401, """
                {"error":{"message":"凭据无效 exact-test-key apiKey=another-test-key Bearer bearer-value eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature https://internal.invalid/v1 /srv/internal/config java.lang.IllegalStateException: hidden-stack","code":"invalid_api_key"},"password":"outside-field"}
                """);
        assertThat(ModelFailureSummary.describe(failure, "exact-test-key"))
                .contains("HTTP 401", "凭据无效", "<已脱敏>")
                .doesNotContain("exact-test-key", "another-test-key", "bearer-value", "eyJ", "internal.invalid", "/srv", "java.lang", "hidden-stack", "outside-field");
    }

    @Test
    void 损坏非JSON过长或缺失响应只返回安全兜底() {
        for (String body : new String[]{"<html>password=unsafe</html>", "{broken", "\"raw-string\"", "null", "{}", "x".repeat(32769)}) {
            String result = ModelFailureSummary.describe(new HttpTransportException("unsafe-message", 502, body), "test-key");
            assertThat(result).contains("HTTP 502", "未提供可展示").doesNotContain("unsafe", "raw-string", "broken", "<html>");
        }
        assertThat(ModelFailureSummary.describe(new ModelException("unsafe-message"), "test-key"))
                .contains("模型接口请求失败").doesNotContain("unsafe-message");
    }

    @Test
    void 支持顶层错误与仅错误码并在脱敏后按Unicode限长() {
        assertThat(ModelFailureSummary.describe(new HttpTransportException("", 429, "{\"message\":\"限流\",\"code\":\"rate_limit\"}"), "key"))
                .contains("限流", "rate_limit");
        assertThat(ModelFailureSummary.describe(new HttpTransportException("", 500, "{\"error\":{\"code\":\"upstream_error\"}}"), "key"))
                .contains("upstream_error");
        String result = ModelFailureSummary.describe(new HttpTransportException("", 500,
                "{\"message\":\"" + "长🙂".repeat(600) + "\"}"), "key");
        assertThat(result.codePointCount(0, result.length())).isLessThan(550);
        assertThat(result).endsWith("…");
    }
}
