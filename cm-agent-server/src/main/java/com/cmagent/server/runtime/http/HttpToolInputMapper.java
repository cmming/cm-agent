package com.cmagent.server.runtime.http;

import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolConfig;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ContainerNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class HttpToolInputMapper {
    private final ObjectMapper objectMapper;
    private final HttpToolConfigValidator configValidator;

    public HttpToolInputMapper(ObjectMapper objectMapper, HttpToolConfigValidator configValidator) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.configValidator = Objects.requireNonNull(configValidator, "configValidator 不能为空");
    }

    public PreparedHttpToolRequest map(HttpToolConfig config, JsonNode input) {
        Objects.requireNonNull(config, "config 不能为空");
        JsonNode rootSchema = configValidator.parseAndValidateSchema(config);
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("工具输入必须是 JSON object");
        }
        ObjectNode effectiveInput = ((ObjectNode) input).deepCopy();
        applyDefaults(config, rootSchema, effectiveInput);
        ensureRequiredMappings(config, effectiveInput);
        Schema schema = configValidator.compile(rootSchema);
        if (!schema.validate(effectiveInput).isEmpty()) {
            throw new IllegalArgumentException("工具输入不符合 inputSchema");
        }
        return mapValues(config, effectiveInput);
    }

    private void applyDefaults(HttpToolConfig config, JsonNode rootSchema, ObjectNode effectiveInput) {
        for (HttpParameterMapping mapping : config.parameterMappings()) {
            JsonNode value = effectiveInput.at(JsonPointer.compile(mapping.sourcePointer()));
            if ((!value.isMissingNode() && !value.isNull()) || !mapping.hasDefaultValue()) {
                continue;
            }
            setInputPath(effectiveInput, rootSchema, HttpToolConfigValidator.pointerTokens(
                    mapping.sourcePointer(), "sourcePointer"), parseDefault(mapping.defaultValueJson()));
        }
    }

    private void ensureRequiredMappings(HttpToolConfig config, ObjectNode effectiveInput) {
        for (HttpParameterMapping mapping : config.parameterMappings()) {
            JsonNode value = effectiveInput.at(JsonPointer.compile(mapping.sourcePointer()));
            if (mapping.required() && (value.isMissingNode() || value.isNull())) {
                throw new IllegalArgumentException("必填参数缺失");
            }
        }
    }

    private PreparedHttpToolRequest mapValues(HttpToolConfig config, ObjectNode effectiveInput) {
        Map<String, String> pathValues = new LinkedHashMap<>();
        Map<String, List<String>> queryValues = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        ObjectNode body = objectMapper.createObjectNode();
        boolean hasBody = false;

        for (HttpParameterMapping mapping : config.parameterMappings()) {
            JsonNode value = effectiveInput.at(JsonPointer.compile(mapping.sourcePointer()));
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            switch (mapping.location()) {
                case PATH -> pathValues.put(mapping.targetName(), scalarText(value));
                case HEADER -> headers.put(mapping.targetName(), scalarText(value));
                case QUERY -> queryValues.put(mapping.targetName(), queryTexts(value));
                case BODY -> {
                    setObjectPath(body, HttpToolConfigValidator.pointerTokens(
                            mapping.targetPointer(), "targetPointer"), value.deepCopy());
                    hasBody = true;
                }
            }
        }
        return new PreparedHttpToolRequest(pathValues, queryValues, headers, hasBody ? body : null);
    }

    private JsonNode parseDefault(String defaultValueJson) {
        try {
            return objectMapper.readTree(defaultValueJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("defaultValueJson 必须是合法 JSON 值");
        }
    }

    private static String scalarText(JsonNode value) {
        return value.isTextual() ? value.textValue() : value.asText();
    }

    private static List<String> queryTexts(JsonNode value) {
        if (!value.isArray()) {
            return List.of(scalarText(value));
        }
        List<String> values = new ArrayList<>(value.size());
        value.forEach(item -> values.add(scalarText(item)));
        return List.copyOf(values);
    }

    private static void setObjectPath(ObjectNode root, List<String> tokens, JsonNode value) {
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("JSON Pointer 不能指向根节点");
        }
        ContainerNode<?> current = root;
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            boolean last = index == tokens.size() - 1;
            if (current instanceof ObjectNode objectNode) {
                if (last) {
                    objectNode.set(token, value.deepCopy());
                    return;
                }
                JsonNode child = objectNode.get(token);
                if (child == null || child.isNull()) {
                    child = isArrayIndex(tokens.get(index + 1))
                            ? objectNode.putArray(token) : objectNode.putObject(token);
                }
                if (!(child instanceof ContainerNode<?> container)) {
                    throw new IllegalArgumentException("JSON Pointer 路径存在类型冲突");
                }
                current = container;
                continue;
            }
            ArrayNode arrayNode = (ArrayNode) current;
            int arrayIndex = parseArrayIndex(token);
            while (arrayNode.size() <= arrayIndex) {
                arrayNode.addNull();
            }
            if (last) {
                arrayNode.set(arrayIndex, value.deepCopy());
                return;
            }
            JsonNode child = arrayNode.get(arrayIndex);
            if (child == null || child.isNull()) {
                child = isArrayIndex(tokens.get(index + 1))
                        ? objectMapperArrayNode(arrayNode) : objectMapperObjectNode(arrayNode);
                arrayNode.set(arrayIndex, child);
            }
            if (!(child instanceof ContainerNode<?> container)) {
                throw new IllegalArgumentException("JSON Pointer 路径存在类型冲突");
            }
            current = container;
        }
    }

    private void setInputPath(ObjectNode root, JsonNode rootSchema, List<String> tokens, JsonNode value) {
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("JSON Pointer 不能指向根节点");
        }
        ContainerNode<?> current = root;
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            boolean last = index == tokens.size() - 1;
            if (current instanceof ObjectNode objectNode) {
                if (last) {
                    objectNode.set(token, value.deepCopy());
                    return;
                }
                JsonNode child = objectNode.get(token);
                if (child == null || child.isNull()) {
                    child = shouldCreateArray(rootSchema, tokens, index)
                            ? objectNode.putArray(token) : objectNode.putObject(token);
                }
                if (!(child instanceof ContainerNode<?> container)) {
                    throw new IllegalArgumentException("JSON Pointer 路径存在类型冲突");
                }
                current = container;
                continue;
            }
            ArrayNode arrayNode = (ArrayNode) current;
            int arrayIndex = parseArrayIndex(token);
            while (arrayNode.size() <= arrayIndex) {
                arrayNode.addNull();
            }
            if (last) {
                arrayNode.set(arrayIndex, value.deepCopy());
                return;
            }
            JsonNode child = arrayNode.get(arrayIndex);
            if (child == null || child.isNull()) {
                child = shouldCreateArray(rootSchema, tokens, index)
                        ? objectMapperArrayNode(arrayNode) : objectMapperObjectNode(arrayNode);
                arrayNode.set(arrayIndex, child);
            }
            if (!(child instanceof ContainerNode<?> container)) {
                throw new IllegalArgumentException("JSON Pointer 路径存在类型冲突");
            }
            current = container;
        }
    }

    private boolean shouldCreateArray(JsonNode rootSchema, List<String> tokens, int inclusiveIndex) {
        StringBuilder pointer = new StringBuilder();
        for (int index = 0; index <= inclusiveIndex; index++) {
            pointer.append('/').append(tokens.get(index).replace("~", "~0").replace("/", "~1"));
        }
        return configValidator.isArrayNode(configValidator.schemaAt(rootSchema, pointer.toString()));
    }

    private static ArrayNode objectMapperArrayNode(ArrayNode parent) {
        return parent.arrayNode();
    }

    private static ObjectNode objectMapperObjectNode(ArrayNode parent) {
        return parent.objectNode();
    }

    private static boolean isArrayIndex(String token) {
        if (token.isEmpty() || (token.length() > 1 && token.charAt(0) == '0')) {
            return false;
        }
        for (int index = 0; index < token.length(); index++) {
            if (!Character.isDigit(token.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static int parseArrayIndex(String token) {
        if (!isArrayIndex(token)) {
            throw new IllegalArgumentException("JSON Pointer 数组索引无效");
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("JSON Pointer 数组索引无效");
        }
    }
}
