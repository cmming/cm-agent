package com.cmagent.server.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 已完成安全校验和参数映射、可交给 HTTP 客户端发送的请求。
 */
public record PreparedHttpToolRequest(
        Map<String, String> pathValues,
        Map<String, List<String>> queryValues,
        Map<String, String> headers,
        JsonNode body
) {
    /**
     * 校验并构造 {@code PreparedHttpToolRequest} 实例。
     *
     * @param pathValues 已解析并等待替换到 URL 模板的路径参数
     * @param queryValues 已解析并等待编码到查询串的参数
     * @param headers 已解析并等待写入请求的 HTTP 请求头
     * @param body 已按映射规则组装的请求体 JSON
     */
    public PreparedHttpToolRequest {
        pathValues = Map.copyOf(pathValues);
        headers = Map.copyOf(headers);
        queryValues = queryValues.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())
        ));
        body = body == null ? NullNode.getInstance() : body.deepCopy();
    }
}
