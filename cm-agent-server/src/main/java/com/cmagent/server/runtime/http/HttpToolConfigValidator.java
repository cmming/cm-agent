package com.cmagent.server.runtime.http;

import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolConfig;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InvalidSchemaRefException;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaException;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
/** 校验动态 HTTP 工具的协议、Schema、映射、超时和安全边界。 */
public class HttpToolConfigValidator {
    private static final Pattern PATH_PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
    private static final Set<String> FORBIDDEN_DYNAMIC_HEADERS = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "authorization", "cookie",
            "proxy-authorization", "upgrade"
    );

    private final ObjectMapper objectMapper;
    private final SchemaRegistry schemaRegistry;
    private final Schema metaSchema;
    private final HttpToolSchemaNavigator schemaNavigator;

    public HttpToolConfigValidator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        this.metaSchema = schemaRegistry.getSchema(SchemaLocation.of(
                SpecificationVersion.DRAFT_2020_12.getDialectId()
        ));
        this.schemaNavigator = new HttpToolSchemaNavigator(objectMapper);
    }

    /**
     * 校验动态 HTTP 工具配置是否满足运行时和安全约束。
     *
     * @param config 待校验的 HTTP 工具配置
     * @throws IllegalArgumentException 协议、Schema、映射或超时配置不合法时抛出
     */
    public void validate(HttpToolConfig config) {
        Objects.requireNonNull(config, "config 不能为空");
        JsonNode rootSchema = parseJsonStrict(config.inputSchema(), "inputSchema 不是合法 JSON");
        validateSchemaDocument(rootSchema);
        compile(rootSchema);
        validateMappings(config, rootSchema);
    }

    JsonNode parseAndValidateSchema(HttpToolConfig config) {
        validate(config);
        return parseJsonStrict(config.inputSchema(), "inputSchema 不是合法 JSON");
    }

    Schema compile(JsonNode schemaNode) {
        try {
            return schemaRegistry.getSchema(schemaNode);
        } catch (InvalidSchemaRefException exception) {
            throw exception;
        } catch (SchemaException exception) {
            throw new IllegalArgumentException("JSON Schema 无效", exception);
        }
    }

    boolean isArrayAt(JsonNode rootSchema, String sourcePointer) {
        HttpToolSchemaNavigator.SchemaShape shape = schemaNavigator.analyzeSourceShape(
                rootSchema, sourcePointer
        );
        if (shape.hasNoKnownNonNullType()) {
            throw new IllegalArgumentException("sourcePointer 对应 Schema 类型不明确");
        }
        if (shape.isOnlyArray()) {
            return true;
        }
        if (shape.hasArrayAlternative()) {
            throw new IllegalArgumentException("sourcePointer 对应 Schema 容器类型不明确");
        }
        return false;
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

    private JsonNode parseJsonStrict(String value, String errorMessage) {
        try {
            JsonNode parsed = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(value);
            if (parsed == null) {
                throw new IllegalArgumentException(errorMessage);
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    private void validateSchemaDocument(JsonNode rootSchema) {
        if (!rootSchema.isObject()) {
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
        JsonNode type = rootSchema.get("type");
        if (type == null || !type.isTextual() || !"object".equals(type.asText())) {
            throw new IllegalArgumentException("inputSchema 根必须声明为 object");
        }
    }

    private void validateMappings(HttpToolConfig config, JsonNode rootSchema) {
        Set<String> targets = new HashSet<>();
        List<List<String>> bodyTargets = new ArrayList<>();
        Map<List<String>, ContainerShape> bodyContainerShapes = new LinkedHashMap<>();
        Set<String> pathTargets = new LinkedHashSet<>();

        schemaNavigator.validateTerminalLocalReferences(
                rootSchema,
                config.parameterMappings().stream().map(HttpParameterMapping::sourcePointer).toList()
        );
        for (HttpParameterMapping mapping : config.parameterMappings()) {
            validateDefault(mapping, rootSchema);
            validateLocationType(mapping, rootSchema);
            validateTarget(mapping, targets, bodyTargets, bodyContainerShapes, pathTargets);
        }

        if (!extractPathPlaceholders(config.urlTemplate()).equals(pathTargets)) {
            throw new IllegalArgumentException("PATH 映射必须完整匹配 URL 占位符");
        }
    }

    private void validateDefault(HttpParameterMapping mapping, JsonNode rootSchema) {
        if (!mapping.hasDefaultValue()) {
            return;
        }
        JsonNode defaultValue = parseJsonStrict(
                mapping.defaultValueJson(), "defaultValueJson 必须是合法 JSON 值"
        );
        if (defaultValue.isNull()) {
            throw new IllegalArgumentException("defaultValueJson 不能为 null");
        }
        ObjectNode projectedRoot = objectMapper.createObjectNode();
        projectedRoot.put("$schema", SpecificationVersion.DRAFT_2020_12.getDialectId());
        copyDefinitionKeyword(rootSchema, projectedRoot, "$defs");
        copyDefinitionKeyword(rootSchema, projectedRoot, "definitions");
        projectedRoot.set("allOf", objectMapper.createArrayNode().add(
                schemaNavigator.projectSourceSchema(rootSchema, mapping.sourcePointer())
        ));
        if (!compile(projectedRoot).validate(defaultValue).isEmpty()) {
            throw new IllegalArgumentException("defaultValueJson 不符合对应 Schema");
        }
    }

    private static void copyDefinitionKeyword(JsonNode source, ObjectNode target, String keyword) {
        if (source.has(keyword)) {
            target.set(keyword, source.get(keyword).deepCopy());
        }
    }

    private void validateLocationType(HttpParameterMapping mapping, JsonNode rootSchema) {
        if (mapping.location() == HttpParameterLocation.BODY) {
            return;
        }
        HttpToolSchemaNavigator.SchemaShape shape = schemaNavigator.analyzeSourceShape(
                rootSchema, mapping.sourcePointer()
        );
        if (shape.hasNoKnownNonNullType()) {
            throw new IllegalArgumentException("sourcePointer 对应 Schema 类型不明确");
        }
        if (mapping.location() == HttpParameterLocation.QUERY) {
            if (!shape.isQuerySafe()) {
                throw new IllegalArgumentException("QUERY 只允许标量或标量数组，object/array 参数只允许映射到 BODY");
            }
        } else if (!shape.isScalarSafe()) {
            throw new IllegalArgumentException("object/array 参数只允许映射到 BODY");
        }
    }

    private void validateTarget(
            HttpParameterMapping mapping,
            Set<String> targets,
            List<List<String>> bodyTargets,
            Map<List<String>, ContainerShape> bodyContainerShapes,
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
            validateBodyContainerShapes(target, bodyContainerShapes);
            bodyTargets.add(target);
            return;
        }

        String targetName = mapping.location() == HttpParameterLocation.HEADER
                ? mapping.targetName().toLowerCase(Locale.ROOT) : mapping.targetName();
        if (!targets.add(mapping.location() + ":" + targetName)) {
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

    private void validateBodyContainerShapes(
            List<String> target,
            Map<List<String>, ContainerShape> bodyContainerShapes
    ) {
        for (int index = 0; index < target.size() - 1; index++) {
            List<String> prefix = List.copyOf(target.subList(0, index + 1));
            HttpToolArrayIndex.ParseResult arrayIndex = HttpToolArrayIndex.parse(target.get(index + 1));
            ContainerShape required = arrayIndex.requiresArrayContainer()
                    ? ContainerShape.ARRAY : ContainerShape.OBJECT;
            ContainerShape existing = bodyContainerShapes.putIfAbsent(prefix, required);
            if (existing != null && existing != required) {
                throw new IllegalArgumentException("BODY targetPointer 中间容器类型冲突");
            }
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

    private enum ContainerShape {
        OBJECT,
        ARRAY
    }
}
