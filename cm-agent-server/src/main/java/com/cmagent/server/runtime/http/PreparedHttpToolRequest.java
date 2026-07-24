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
    public PreparedHttpToolRequest {
        pathValues = Map.copyOf(pathValues);
        headers = Map.copyOf(headers);
        queryValues = queryValues.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())
        ));
        body = body == null ? NullNode.getInstance() : body.deepCopy();
    }
}
