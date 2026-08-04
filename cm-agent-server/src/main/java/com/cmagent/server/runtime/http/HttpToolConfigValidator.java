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
    /**
     * 创建 {@code HttpToolConfigValidator} 实例并保存其运行所需依赖。
     *
     * @param objectMapper JSON 映射器，用于序列化或解析 JSON。
     */
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
    /**
     * 严格解析并校验工具输入 JSON Schema。
     *
     * @param config 待解析并完整校验的动态 HTTP 工具配置
     */
    JsonNode parseAndValidateSchema(HttpToolConfig config) {
        validate(config);
        return parseJsonStrict(config.inputSchema(), "inputSchema 不是合法 JSON");
    }
    /**
     * 编译 JSON Schema，供运行时输入校验复用。
     *
     * @param schemaNode 当前分析的 Schema 节点。
     */
    Schema compile(JsonNode schemaNode) {
        try {
            return schemaRegistry.getSchema(schemaNode);
        } catch (InvalidSchemaRefException exception) {
            throw exception;
        } catch (SchemaException exception) {
            throw new IllegalArgumentException("JSON Schema 无效", exception);
        }
    }
    /**
     * 判断指定 JSON Pointer 在 Schema 中是否指向数组。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param sourcePointer 参数映射使用的源 JSON Pointer。
     */
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
    /**
     * 将 JSON Pointer 拆分为已反转义的路径片段。
     *
     * @param pointer 待解析的 JSON Pointer。
     * @param fieldName 当前遍历的 Schema 字段名。
     */
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

    /**
     * 严格解析 JSON，拒绝重复字段和尾随内容。
     *
     * @param value 待检查、转换或规范化的值。
     * @param errorMessage 配置不合法时使用的受控错误说明。
     */
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

    /**
     * 校验 Schema 根节点和允许使用的关键字。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     */
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

    /**
     * 校验参数映射的源路径、目标位置和重复冲突。
     *
     * @param config 提供输入 Schema 和参数映射规则的动态 HTTP 工具配置
     * @param rootSchema 工具输入的根 JSON Schema。
     */
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

    /**
     * 校验映射默认值与源 Schema 类型兼容。
     *
     * @param mapping 当前 HTTP 参数映射。
     * @param rootSchema 工具输入的根 JSON Schema。
     */
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

    /**
     * 复制并规范化 definitions 或 $defs 关键字。
     *
     * @param source 待转换或复制的源节点。
     * @param target 目标 JSON 节点、路径或业务对象。
     * @param keyword 当前处理的 JSON Schema 关键字。
     */
    private static void copyDefinitionKeyword(JsonNode source, ObjectNode target, String keyword) {
        if (source.has(keyword)) {
            target.set(keyword, source.get(keyword).deepCopy());
        }
    }

    /**
     * 校验参数值类型适合目标 HTTP 位置。
     *
     * @param mapping 当前 HTTP 参数映射。
     * @param rootSchema 工具输入的根 JSON Schema。
     */
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

    /**
     * 校验目标名称、路径占位符和请求体指针。
     *
     * @param mapping 当前 HTTP 参数映射。
     * @param targets 已经占用的目标位置集合。
     * @param bodyTargets 已经占用的 BODY JSON Pointer 集合。
     * @param bodyContainerShapes 各 BODY 路径要求的容器形态索引。
     * @param pathTargets URL 模板中已声明的路径占位符集合。
     */
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

    /**
     * 校验多个 BODY 映射不会要求冲突的容器类型。
     *
     * @param target 目标 JSON 节点、路径或业务对象。
     * @param bodyContainerShapes 各 BODY 路径要求的容器形态索引。
     */
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

    /**
     * 提取 URL 模板中的路径占位符集合。
     *
     * @param urlTemplate HTTP 工具配置的 URL 模板。
     */
    private static Set<String> extractPathPlaceholders(String urlTemplate) {
        Set<String> placeholders = new LinkedHashSet<>();
        Matcher matcher = PATH_PLACEHOLDER.matcher(urlTemplate);
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        return Set.copyOf(placeholders);
    }

    /**
     * 判断一个 JSON Pointer 片段序列是否为另一个的前缀。
     *
     * @param prefix 待匹配的网络或路径前缀。
     * @param value 待检查、转换或规范化的值。
     */
    private static boolean isPrefix(List<String> prefix, List<String> value) {
        return prefix.size() <= value.size() && value.subList(0, prefix.size()).equals(prefix);
    }

    /**
     * 封装 {@code ContainerShape} 在 HTTP 工具流程中使用的不可变数据。
     */
    private enum ContainerShape {
        /** 当前节点按 JSON 对象容器处理。 */
        OBJECT,
        /** 当前节点按 JSON 数组容器处理。 */
        ARRAY
    }
}
