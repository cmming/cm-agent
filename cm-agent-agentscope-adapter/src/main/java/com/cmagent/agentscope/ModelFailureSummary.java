package com.cmagent.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.transport.HttpTransportException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 将不可信模型错误响应缩减为可持久化、可返回前端的说明，不转发完整响应或异常消息。
 * 只读取 JSON 中的错误说明与代码，其他字段即使包含请求回显也不会进入结果。
 */
final class ModelFailureSummary {
    /** 限制待解析错误响应，避免外部大响应占用过多资源。 */
    private static final int MAX_BODY_LENGTH = 32768;
    /** 脱敏后再限长，避免先截断导致密钥正则无法匹配完整内容。 */
    private static final int MAX_MESSAGE_LENGTH = 512;
    /** 统一替换密钥、令牌、地址与路径，不把内部运维信息暴露给前端。 */
    private static final String MASK = "<已脱敏>";
    /** 清理上游错误说明中的凭据赋值，包括 JSON 片段与普通文本。 */
    private static final Pattern SECRETS = Pattern.compile(
            "(?i)\\\"?(?:password|passwd|api[-_]?key|jwt[-_]?secret|secret|token|authorization|cookie)\\\"?\\s*[:=]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}]+)");
    /** 令牌也可能独立出现，不能只依赖带字段名的赋值匹配。 */
    private static final Pattern TOKENS = Pattern.compile(
            "(?i)Bearer\\s+[^\\s,;]+|\\bsk-[a-z0-9_-]+|\\beyJ[a-z0-9_-]+\\.[a-z0-9_-]+\\.[a-z0-9_-]+");
    /** 隐藏服务地址、数据库地址和常见宿主路径，避免错误说明泄露部署细节。 */
    private static final Pattern ADDRESSES = Pattern.compile(
            "(?i)(?:jdbc:)?https?://[^\\s\\\"']+|jdbc:(?:mysql|postgresql)://[^\\s\\\"']+|(?:[a-z]:[\\\\/]|/(?:etc|var|home|root|tmp|opt|srv|app)/)[^\\s\\\"',;]+");
    /** 上游可能把内部异常当作 message 返回；依赖类名与堆栈不能进入前端。 */
    private static final Pattern STACK = Pattern.compile(
            "(?m)\\bat\\s+[\\w.$]+\\([^\\r\\n]*\\)|(?:[a-z_$][\\w$]*\\.)*[A-Z][\\w$]*(?:Exception|Error)(?::[^\\r\\n]*)?");

    private ModelFailureSummary() { }

    /**
     * 从已识别的 Provider 异常链提取 HTTP 状态与安全摘要；未知文本、HTML 和无响应时给出明确兜底。
     *
     * @param failure 原失败，仅用于识别类型、HTTP 状态和结构化响应，禁止直接复制其 message
     * @param apiKey 当前调用实际使用的密钥，只用于精确删除上游可能回显的值，绝不记录
     * @return 已脱敏且限长的中文说明
     */
    static String describe(Throwable failure, String apiKey) {
        Integer status = null;
        String body = null;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof HttpTransportException transport) {
                if (status == null) status = transport.getStatusCode();
                if (body == null) body = transport.getResponseBody();
            } else if (current instanceof ModelHttpException http) {
                if (status == null) status = http.getStatusCode();
                if (body == null) body = providerBody(current);
            } else if (current.getClass().getName().equals(
                    "io.agentscope.extensions.model.dashscope.DashScopeHttpClient$DashScopeHttpException")) {
                // AgentScope 2.0.2 的这个公开内部异常未实现 ModelHttpException，只对已核实的类型读取同名公开方法。
                if (status == null) status = providerStatus(current);
                if (body == null) body = providerBody(current);
            }
        }
        String prefix = status != null && status >= 100 && status <= 599
                ? "模型接口请求失败（HTTP " + status + "）" : "模型接口请求失败";
        String summary = structuredMessage(body);
        if (summary.isBlank()) return prefix + "：上游未提供可展示的结构化错误说明，请核对模型配置或联系管理员。";
        if (apiKey != null && !apiKey.isEmpty()) summary = summary.replace(apiKey, MASK);
        summary = SECRETS.matcher(summary).replaceAll(MASK);
        summary = TOKENS.matcher(summary).replaceAll(MASK);
        summary = ADDRESSES.matcher(summary).replaceAll(MASK);
        summary = STACK.matcher(summary).replaceAll(MASK).replaceAll("[\\p{Cntrl}]+", " ").strip();
        int count = summary.codePointCount(0, summary.length());
        if (count > MAX_MESSAGE_LENGTH) summary = summary.substring(0, summary.offsetByCodePoints(0, MAX_MESSAGE_LENGTH)) + "…";
        return prefix + "：" + summary;
    }

    /**
     * AgentScope 2.0.2 的 ModelHttpException 仅承诺状态码，OpenAIException 另外提供 getResponseBody。
     * DashScopeHttpClient.DashScopeHttpException 则独立提供 getStatusCode/getResponseBody，没有实现该接口。
     * 反射读取这个已核实的公开方法以保留 Provider 可选依赖，未安装 OpenAI 扩展也不会因类型引用失败。
     * 不支持该方法或读取失败时拒绝回退到异常消息，避免把堆栈或原始请求内容当作响应展示。
     */
    private static String providerBody(Throwable failure) {
        try {
            Object value = failure.getClass().getMethod("getResponseBody").invoke(failure);
            return value instanceof String text ? text : null;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    /** 对已核实的 DashScope 异常读取 HTTP 状态；缺失或读取失败仍按无 HTTP 状态处理。 */
    private static Integer providerStatus(Throwable failure) {
        try {
            Object value = failure.getClass().getMethod("getStatusCode").invoke(failure);
            return value instanceof Integer status ? status : null;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    /** 只接受错误对象的 message/code/type 或顶层 message/code；绝不回传其他响应字段。 */
    private static String structuredMessage(String body) {
        if (body == null || body.isBlank() || body.length() > MAX_BODY_LENGTH) return "";
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            if (root == null || !root.isObject()) return "";
            JsonNode error = root.path("error");
            JsonNode source = error.isObject() ? error : root;
            String message = source.path("message").isTextual() ? source.path("message").asText() : "";
            String code = source.path("code").isTextual() ? source.path("code").asText() : "";
            if (code.isBlank() && error.isObject() && source.path("type").isTextual()) code = source.path("type").asText();
            return message.isBlank() ? code : code.isBlank() ? message : message + "（上游错误码：" + code + "）";
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // 代理服务器可能返回 HTML 或损坏的 JSON；失败关闭，不把原文或解析异常返给用户。
            return "";
        }
    }
}
