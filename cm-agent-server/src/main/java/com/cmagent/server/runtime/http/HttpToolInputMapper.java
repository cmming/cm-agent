package com.cmagent.server.runtime.http;

import com.cmagent.core.domain.HttpParameterDataType;
import com.cmagent.core.domain.HttpParameterDefinition;
import com.cmagent.core.domain.HttpToolConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
/** 按扁平参数定义将输入写入 PATH、QUERY、HEADER 和 BODY。 */
public class HttpToolInputMapper {
    private final ObjectMapper objectMapper;
    private final HttpToolConfigValidator configValidator;

    /**
     * 创建 HTTP 工具输入映射器。
     *
     * @param objectMapper JSON 映射器
     * @param configValidator HTTP 参数定义校验器
     */
    public HttpToolInputMapper(ObjectMapper objectMapper, HttpToolConfigValidator configValidator) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.configValidator = Objects.requireNonNull(configValidator, "configValidator 不能为空");
    }

    /**
     * 将工具输入映射为待发送的 HTTP 请求。
     *
     * @param config HTTP 工具配置
     * @param input 调用方提供的 JSON 输入
     * @return 完成位置映射的 HTTP 请求
     */
    public PreparedHttpToolRequest map(HttpToolConfig config, JsonNode input) {
        Objects.requireNonNull(config, "config 不能为空");
        JsonNode rootSchema = configValidator.parseAndValidateSchema(config);
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("工具输入必须是 JSON object");
        }
        ObjectNode effectiveInput = ((ObjectNode) input).deepCopy();
        applyDefinitionDefaults(config, effectiveInput);
        Schema schema = configValidator.compile(rootSchema);
        if (!schema.validate(effectiveInput).isEmpty()) {
            throw new IllegalArgumentException("工具输入不符合 parameters 生成的 inputSchema");
        }
        return mapDefinitionValues(config, effectiveInput);
    }

    /** 按参数树递归应用顶层、对象和对象数组中的默认值。 */
    private void applyDefinitionDefaults(HttpToolConfig config, ObjectNode effectiveInput) {
        HttpParameterDefinitionCompiler.ParameterTree tree = configValidator.parameterTree(config.parameters());
        for (HttpParameterDefinition root : tree.roots()) {
            applyNamedDefinitionDefault(effectiveInput, root, tree);
        }
    }

    private void applyNamedDefinitionDefault(
            ObjectNode parent,
            HttpParameterDefinition definition,
            HttpParameterDefinitionCompiler.ParameterTree tree
    ) {
        JsonNode value = parent.get(definition.name());
        if (value == null || value.isNull()) {
            if (definition.hasDefaultValue()) {
                value = parseDefault(definition.defaultValueJson());
                parent.set(definition.name(), value.deepCopy());
            } else if (definition.dataType() == HttpParameterDataType.OBJECT
                    && hasDescendantDefault(definition, tree)) {
                value = parent.putObject(definition.name());
            } else {
                return;
            }
        }
        applyContainerDefaults(value, definition, tree);
    }

    private void applyContainerDefaults(
            JsonNode value,
            HttpParameterDefinition definition,
            HttpParameterDefinitionCompiler.ParameterTree tree
    ) {
        if (definition.dataType() == HttpParameterDataType.OBJECT && value instanceof ObjectNode objectValue) {
            for (HttpParameterDefinition child : tree.childrenOf(definition.id())) {
                applyNamedDefinitionDefault(objectValue, child, tree);
            }
            return;
        }
        if (definition.dataType() == HttpParameterDataType.ARRAY && value instanceof ArrayNode arrayValue) {
            HttpParameterDefinition itemDefinition = tree.childrenOf(definition.id()).getFirst();
            for (JsonNode item : arrayValue) {
                applyContainerDefaults(item, itemDefinition, tree);
            }
        }
    }

    private boolean hasDescendantDefault(
            HttpParameterDefinition definition,
            HttpParameterDefinitionCompiler.ParameterTree tree
    ) {
        for (HttpParameterDefinition child : tree.childrenOf(definition.id())) {
            if (child.hasDefaultValue() || hasDescendantDefault(child, tree)) {
                return true;
            }
        }
        return false;
    }

    /** 按顶层字段的位置约定构造 HTTP 请求。 */
    private PreparedHttpToolRequest mapDefinitionValues(HttpToolConfig config, ObjectNode effectiveInput) {
        HttpParameterDefinitionCompiler.ParameterTree tree = configValidator.parameterTree(config.parameters());
        Map<String, String> pathValues = new LinkedHashMap<>();
        Map<String, List<String>> queryValues = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        ObjectNode bodyObject = objectMapper.createObjectNode();
        JsonNode bodyRoot = null;
        boolean hasBody = false;
        for (HttpParameterDefinition definition : tree.roots()) {
            JsonNode value = effectiveInput.get(definition.name());
            if (value == null || value.isNull()) {
                continue;
            }
            switch (definition.requestLocation()) {
                case PATH -> pathValues.put(definition.name(), scalarText(value));
                case QUERY -> queryValues.put(definition.name(), queryTexts(value));
                case HEADER -> headers.put(definition.name(), scalarText(value));
                case BODY -> {
                    bodyObject.set(definition.name(), value.deepCopy());
                    hasBody = true;
                }
                case BODY_ROOT -> bodyRoot = value.deepCopy();
            }
        }
        return new PreparedHttpToolRequest(
                pathValues,
                queryValues,
                headers,
                bodyRoot != null ? bodyRoot : hasBody ? bodyObject : null
        );
    }

    private JsonNode parseDefault(String defaultValueJson) {
        try {
            return objectMapper.readTree(defaultValueJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("defaultValue 必须是合法 JSON 值", exception);
        }
    }

    private static String scalarText(JsonNode value) {
        if (!value.isValueNode() || value.isNull()) {
            throw new IllegalArgumentException("非 BODY 参数必须是标量值");
        }
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
}
