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
/** 按 JSON Pointer 将输入参数映射到 PATH、QUERY、HEADER 和 BODY。 */
public class HttpToolInputMapper {
    private final ObjectMapper objectMapper;
    private final HttpToolConfigValidator configValidator;
    /**
     * 创建 {@code HttpToolInputMapper} 实例并保存其运行所需依赖。
     *
     * @param objectMapper JSON 映射器，用于序列化或解析 JSON。
     * @param configValidator 复用严格 JSON Schema 校验规则的组件。
     */
    public HttpToolInputMapper(ObjectMapper objectMapper, HttpToolConfigValidator configValidator) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.configValidator = Objects.requireNonNull(configValidator, "configValidator 不能为空");
    }

    /**
     * 将工具输入映射为待发送的 HTTP 请求。
     *
     * @param config HTTP 工具配置
     * @param input  调用方提供的 JSON 输入
     * @return 完成路径、查询参数、请求头和请求体映射的请求
     * @throws IllegalArgumentException 输入不符合 Schema 或映射规则时抛出
     */
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

    /**
     * 把映射默认值补入缺失或显式为 null 的输入位置。
     *
     * @param config 提供默认值和参数映射规则的动态 HTTP 工具配置
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param effectiveInput 应用默认值后的有效输入副本。
     */
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

    /**
     * 确认所有必填映射在默认值处理后都有输入。
     *
     * @param config 提供必填参数映射规则的动态 HTTP 工具配置
     * @param effectiveInput 应用默认值后的有效输入副本。
     */
    private void ensureRequiredMappings(HttpToolConfig config, ObjectNode effectiveInput) {
        for (HttpParameterMapping mapping : config.parameterMappings()) {
            JsonNode value = effectiveInput.at(JsonPointer.compile(mapping.sourcePointer()));
            if (mapping.required() && (value.isMissingNode() || value.isNull())) {
                throw new IllegalArgumentException("必填参数缺失");
            }
        }
    }

    /**
     * 把输入值按配置分别映射到 PATH、QUERY、HEADER 和 BODY。
     *
     * @param config 提供各 HTTP 位置映射规则的动态 HTTP 工具配置
     * @param effectiveInput 应用默认值后的有效输入副本。
     */
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

    /**
     * 将映射默认值解析为 JSON 节点。
     *
     * @param defaultValueJson 映射配置中的默认值 JSON 文本。
     */
    private JsonNode parseDefault(String defaultValueJson) {
        try {
            return objectMapper.readTree(defaultValueJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("defaultValueJson 必须是合法 JSON 值");
        }
    }

    /**
     * 将标量节点转换为可写入路径或请求头的文本。
     *
     * @param value 待检查、转换或规范化的值。
     */
    private static String scalarText(JsonNode value) {
        if (!value.isValueNode() || value.isNull()) {
            throw new IllegalArgumentException("非 BODY 参数必须是标量值");
        }
        return value.isTextual() ? value.textValue() : value.asText();
    }

    /**
     * 将标量或标量数组转换为查询参数值列表。
     *
     * @param value 待检查、转换或规范化的值。
     */
    private static List<String> queryTexts(JsonNode value) {
        if (!value.isArray()) {
            return List.of(scalarText(value));
        }
        List<String> values = new ArrayList<>(value.size());
        value.forEach(item -> values.add(scalarText(item)));
        return List.copyOf(values);
    }

    /**
     * 沿 JSON Pointer 创建对象层级并写入目标值。
     *
     * @param root 当前处理的 JSON 根节点。
     * @param tokens 拆分并反转义后的路径片段列表。
     * @param value 待检查、转换或规范化的值。
     */
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
                    child = HttpToolArrayIndex.parse(tokens.get(index + 1)).requiresArrayContainer()
                            ? objectNode.putArray(token) : objectNode.putObject(token);
                }
                if (!(child instanceof ContainerNode<?> container)) {
                    throw new IllegalArgumentException("JSON Pointer 路径存在类型冲突");
                }
                current = container;
                continue;
            }
            ArrayNode arrayNode = (ArrayNode) current;
            int arrayIndex = HttpToolArrayIndex.parse(token).requireValue();
            while (arrayNode.size() <= arrayIndex) {
                arrayNode.addNull();
            }
            if (last) {
                arrayNode.set(arrayIndex, value.deepCopy());
                return;
            }
            JsonNode child = arrayNode.get(arrayIndex);
            if (child == null || child.isNull()) {
                child = HttpToolArrayIndex.parse(tokens.get(index + 1)).requiresArrayContainer()
                        ? objectMapperArrayNode(arrayNode) : objectMapperObjectNode(arrayNode);
                arrayNode.set(arrayIndex, child);
            }
            if (!(child instanceof ContainerNode<?> container)) {
                throw new IllegalArgumentException("JSON Pointer 路径存在类型冲突");
            }
            current = container;
        }
    }

    /**
     * 沿输入路径创建对象或数组容器并写入默认值。
     *
     * @param root 当前处理的 JSON 根节点。
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param tokens 拆分并反转义后的路径片段列表。
     * @param value 待检查、转换或规范化的值。
     */
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
            int arrayIndex = HttpToolArrayIndex.parse(token).requireValue();
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

    /**
     * 根据下一路径片段判断是否需要创建数组容器。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param tokens 拆分并反转义后的路径片段列表。
     * @param inclusiveIndex 包含当前位的前缀末端下标。
     */
    private boolean shouldCreateArray(JsonNode rootSchema, List<String> tokens, int inclusiveIndex) {
        StringBuilder pointer = new StringBuilder();
        for (int index = 0; index <= inclusiveIndex; index++) {
            pointer.append('/').append(tokens.get(index).replace("~", "~0").replace("/", "~1"));
        }
        return configValidator.isArrayAt(rootSchema, pointer.toString());
    }

    /**
     * 创建与当前映射器兼容的空数组节点。
     *
     * @param parent 当前路径片段的父容器。
     */
    private static ArrayNode objectMapperArrayNode(ArrayNode parent) {
        return parent.arrayNode();
    }

    /**
     * 创建与当前映射器兼容的空对象节点。
     *
     * @param parent 当前路径片段的父容器。
     */
    private static ObjectNode objectMapperObjectNode(ArrayNode parent) {
        return parent.objectNode();
    }

}
