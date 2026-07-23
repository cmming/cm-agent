package com.cmagent.server.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
