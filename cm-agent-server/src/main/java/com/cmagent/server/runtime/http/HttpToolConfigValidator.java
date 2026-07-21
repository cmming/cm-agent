package com.cmagent.server.runtime.http;

import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class HttpToolConfigValidator {
    private static final Pattern PATH_PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
    private static final Set<String> FORBIDDEN_DYNAMIC_HEADERS = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "authorization", "cookie",
            "proxy-authorization", "upgrade"
    );
    private static final Set<String> SCALAR_TYPES = Set.of("string", "number", "integer", "boolean");

    private final ObjectMapper objectMapper;
    private final SchemaRegistry schemaRegistry;
    private final Schema metaSchema;

    public HttpToolConfigValidator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        this.metaSchema = schemaRegistry.getSchema(SchemaLocation.of(
                SpecificationVersion.DRAFT_2020_12.getDialectId()
        ));
    }

    public void validate(HttpToolConfig config) {
        Objects.requireNonNull(config, "config 不能为空");
        JsonNode rootSchema = parseSchema(config.inputSchema());
        validateSchemaDocument(rootSchema);
        validateMappings(config, rootSchema);
    }

    JsonNode parseAndValidateSchema(HttpToolConfig config) {
        validate(config);
        return parseSchema(config.inputSchema());
    }

    Schema compile(JsonNode schemaNode) {
        try {
            return schemaRegistry.getSchema(schemaNode);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("JSON Schema 无效");
        }
    }

    boolean isArrayNode(JsonNode schemaNode) {
        return isArraySchema(schemaNode);
    }

    JsonNode schemaAt(JsonNode rootSchema, String sourcePointer) {
        List<String> tokens = pointerTokens(sourcePointer, "sourcePointer");
        JsonNode current = resolveReference(rootSchema, rootSchema);
        for (String token : tokens) {
            current = resolveReference(rootSchema, current);
            JsonNode properties = current.get("properties");
            if (properties != null && properties.isObject() && properties.has(token)) {
                current = properties.get(token);
                continue;
            }
            if (isArraySchema(current) && isArrayIndex(token)) {
                int index = Integer.parseInt(token);
                JsonNode prefixItems = current.get("prefixItems");
                if (prefixItems != null && prefixItems.isArray() && index < prefixItems.size()) {
                    current = prefixItems.get(index);
                    continue;
                }
                JsonNode items = current.get("items");
                if (items != null && (items.isObject() || items.isBoolean())) {
                    current = items;
                    continue;
                }
            }
            throw new IllegalArgumentException("sourcePointer 未指向 Schema 中存在的输入节点");
        }
        return resolveReference(rootSchema, current);
    }

    static List<String> pointerTokens(String pointer, String fieldName) {
        if (pointer == null || (!pointer.isEmpty() && !pointer.startsWith("/"))) {
            throw new IllegalArgumentException(fieldName + " 必须是 JSON Pointer");
        }
        for (int index = 0; index < pointer.length(); index++) {
            if (pointer.charAt(index) == '~'
                    && (index + 1 >= pointer.length()
                    || (pointer.charAt(index + 1) != '0' && pointer.charAt(index + 1) != '1'))) {
                throw new IllegalArgumentException(fieldName + " 必须是合法 JSON Pointer");
            }
        }
        try {
            JsonPointer compiled = JsonPointer.compile(pointer);
            List<String> tokens = new ArrayList<>();
            while (!compiled.matches()) {
                tokens.add(compiled.getMatchingProperty());
                compiled = compiled.tail();
            }
            return List.copyOf(tokens);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(fieldName + " 必须是合法 JSON Pointer");
        }
    }

    private JsonNode parseSchema(String inputSchema) {
        try {
            return objectMapper.readTree(inputSchema);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("inputSchema 不是合法 JSON");
        }
    }

    private void validateSchemaDocument(JsonNode rootSchema) {
        if (rootSchema == null || !rootSchema.isObject()) {
            throw new IllegalArgumentException("inputSchema 根必须是 object Schema");
        }
        JsonNode declaredDialect = rootSchema.get("$schema");
        if (declaredDialect != null && (!declaredDialect.isTextual()
                || !SpecificationVersion.DRAFT_2020_12.getDialectId().equals(declaredDialect.asText()))) {
            throw new IllegalArgumentException("inputSchema 必须使用 JSON Schema 2020-12");
        }
        if (!metaSchema.validate(rootSchema).isEmpty()) {
            throw new IllegalArgumentException("JSON Schema 无效");
        }
        if (!declaresOnlyObject(rootSchema)) {
            throw new IllegalArgumentException("inputSchema 根必须声明为 object");
        }
        compile(rootSchema);
    }

    private void validateMappings(HttpToolConfig config, JsonNode rootSchema) {
        Set<String> targets = new HashSet<>();
        List<List<String>> bodyTargets = new ArrayList<>();
        Set<String> pathTargets = new LinkedHashSet<>();

        for (HttpParameterMapping mapping : config.parameterMappings()) {
            JsonNode subSchema = schemaAt(rootSchema, mapping.sourcePointer());
            validateDefault(mapping, subSchema);
            validateLocationType(mapping, subSchema);
            validateTarget(mapping, targets, bodyTargets, pathTargets);
        }

        Set<String> placeholders = extractPathPlaceholders(config.urlTemplate());
        if (!placeholders.equals(pathTargets)) {
            throw new IllegalArgumentException("PATH 映射必须完整匹配 URL 占位符");
        }
    }

    private void validateDefault(HttpParameterMapping mapping, JsonNode subSchema) {
        if (!mapping.hasDefaultValue()) {
            return;
        }
        JsonNode defaultValue;
        try {
            defaultValue = objectMapper.readTree(mapping.defaultValueJson());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("defaultValueJson 必须是合法 JSON 值");
        }
        if (defaultValue == null || !compile(subSchema).validate(defaultValue).isEmpty()) {
            throw new IllegalArgumentException("defaultValueJson 不符合对应 Schema");
        }
    }

    private void validateLocationType(HttpParameterMapping mapping, JsonNode subSchema) {
        if (mapping.location() == HttpParameterLocation.BODY) {
            return;
        }
        Set<String> types = declaredTypes(subSchema);
        if (types.isEmpty() || types.contains("object")) {
            throw new IllegalArgumentException("object/array 参数只允许映射到 BODY");
        }
        if (types.contains("array")) {
            if (mapping.location() != HttpParameterLocation.QUERY || !isScalarArray(subSchema)) {
                throw new IllegalArgumentException("QUERY 只允许标量或标量数组，object/array 参数只允许映射到 BODY");
            }
            return;
        }
        if (!SCALAR_TYPES.containsAll(types)) {
            throw new IllegalArgumentException("PATH、HEADER 仅允许标量，QUERY 仅允许标量或标量数组");
        }
    }

    private void validateTarget(
            HttpParameterMapping mapping,
            Set<String> targets,
            List<List<String>> bodyTargets,
            Set<String> pathTargets
    ) {
        if (mapping.location() == HttpParameterLocation.BODY) {
            List<String> target = pointerTokens(mapping.targetPointer(), "targetPointer");
            if (target.isEmpty()) {
                throw new IllegalArgumentException("BODY targetPointer 不能指向根节点");
            }
            for (List<String> existing : bodyTargets) {
                if (isPrefix(existing, target) || isPrefix(target, existing)) {
                    throw new IllegalArgumentException("BODY targetPointer 存在相等或父子冲突");
                }
            }
            bodyTargets.add(target);
            return;
        }

        String targetName = mapping.location() == HttpParameterLocation.HEADER
                ? mapping.targetName().toLowerCase(Locale.ROOT) : mapping.targetName();
        String targetKey = mapping.location() + ":" + targetName;
        if (!targets.add(targetKey)) {
            throw new IllegalArgumentException("参数映射目标不能重复");
        }
        if (mapping.location() == HttpParameterLocation.HEADER
                && FORBIDDEN_DYNAMIC_HEADERS.contains(targetName)) {
            throw new IllegalArgumentException("动态 Header 不允许覆盖敏感或逐跳请求头");
        }
        if (mapping.location() == HttpParameterLocation.PATH) {
            if (!mapping.required()) {
                throw new IllegalArgumentException("PATH 参数必须为必填");
            }
            pathTargets.add(mapping.targetName());
        }
    }

    private JsonNode resolveReference(JsonNode rootSchema, JsonNode schemaNode) {
        JsonNode current = schemaNode;
        Set<String> seen = new HashSet<>();
        while (current != null && current.isObject() && current.has("$ref")) {
            String reference = current.path("$ref").asText();
            if (!reference.startsWith("#") || !seen.add(reference)) {
                throw new IllegalArgumentException("sourcePointer 对应 Schema 引用无效");
            }
            String fragment = reference.substring(1);
            pointerTokens(fragment, "$ref");
            current = rootSchema.at(JsonPointer.compile(fragment));
            if (current.isMissingNode()) {
                throw new IllegalArgumentException("sourcePointer 对应 Schema 引用不存在");
            }
        }
        return current;
    }

    private static boolean declaresOnlyObject(JsonNode schemaNode) {
        JsonNode type = schemaNode.get("type");
        return type != null && type.isTextual() && "object".equals(type.asText());
    }

    private static boolean isArraySchema(JsonNode schemaNode) {
        return declaredTypes(schemaNode).contains("array");
    }

    private static Set<String> declaredTypes(JsonNode schemaNode) {
        JsonNode type = schemaNode == null ? null : schemaNode.get("type");
        if (type == null) {
            return Set.of();
        }
        if (type.isTextual()) {
            return Set.of(type.asText());
        }
        if (type.isArray()) {
            Set<String> types = new HashSet<>();
            type.forEach(item -> {
                if (item.isTextual() && !"null".equals(item.asText())) {
                    types.add(item.asText());
                }
            });
            return Set.copyOf(types);
        }
        return Set.of();
    }

    private static boolean isScalarArray(JsonNode schemaNode) {
        JsonNode items = schemaNode.get("items");
        if (items == null || !items.isObject()) {
            return false;
        }
        Set<String> itemTypes = declaredTypes(items);
        return !itemTypes.isEmpty() && SCALAR_TYPES.containsAll(itemTypes);
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
        try {
            Integer.parseInt(token);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static Set<String> extractPathPlaceholders(String urlTemplate) {
        Set<String> placeholders = new LinkedHashSet<>();
        Matcher matcher = PATH_PLACEHOLDER.matcher(urlTemplate);
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        return Set.copyOf(placeholders);
    }

    private static boolean isPrefix(List<String> prefix, List<String> value) {
        return prefix.size() <= value.size() && value.subList(0, prefix.size()).equals(prefix);
    }
}
