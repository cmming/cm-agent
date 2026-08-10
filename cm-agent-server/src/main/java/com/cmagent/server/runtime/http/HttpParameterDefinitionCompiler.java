package com.cmagent.server.runtime.http;

import com.cmagent.core.domain.HttpParameterDataType;
import com.cmagent.core.domain.HttpParameterDefinition;
import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpToolMethod;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

/**
 * 校验扁平 HTTP 参数树并生成供 Tool 与 MCP 使用的 JSON Schema。
 */
final class HttpParameterDefinitionCompiler {
    private static final int MAX_PARAMETERS = 512;
    private static final int MAX_DEPTH = 32;
    private static final Pattern PATH_PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
    private static final Set<String> FORBIDDEN_DYNAMIC_HEADERS = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "authorization", "cookie",
            "proxy-authorization", "upgrade"
    );

    private final ObjectMapper objectMapper;

    HttpParameterDefinitionCompiler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 校验参数定义并生成稳定的 JSON Schema 文本。
     */
    String compileInputSchema(
            List<HttpParameterDefinition> parameters,
            HttpToolMethod method,
            String urlTemplate
    ) {
        ParameterTree tree = buildTree(parameters);
        validateLocations(tree, method, urlTemplate);
        ObjectNode rootSchema = objectMapper.createObjectNode();
        rootSchema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        rootSchema.put("type", "object");
        ObjectNode properties = rootSchema.putObject("properties");
        ArrayNode required = rootSchema.putArray("required");
        for (HttpParameterDefinition root : tree.roots()) {
            properties.set(root.name(), schemaFor(root, tree, 1));
            if (root.required()) {
                required.add(root.name());
            }
        }
        if (required.isEmpty()) {
            rootSchema.remove("required");
        }
        rootSchema.put("additionalProperties", false);
        try {
            return objectMapper.writeValueAsString(rootSchema);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("参数定义生成 inputSchema 失败", exception);
        }
    }

    /**
     * 构建并校验树关系，供运行时递归默认值处理复用。
     */
    ParameterTree buildTree(List<HttpParameterDefinition> parameters) {
        List<HttpParameterDefinition> safeParameters = List.copyOf(parameters == null ? List.of() : parameters);
        if (safeParameters.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException("HTTP 参数数量超过安全上限");
        }
        Map<String, HttpParameterDefinition> byId = new LinkedHashMap<>();
        for (HttpParameterDefinition parameter : safeParameters) {
            if (byId.putIfAbsent(parameter.id(), parameter) != null) {
                throw new IllegalArgumentException("参数 id 不能重复");
            }
        }
        Map<String, List<HttpParameterDefinition>> mutableChildren = new LinkedHashMap<>();
        List<HttpParameterDefinition> roots = new ArrayList<>();
        for (HttpParameterDefinition parameter : safeParameters) {
            if (parameter.root()) {
                roots.add(parameter);
                continue;
            }
            if (!byId.containsKey(parameter.parentId())) {
                throw new IllegalArgumentException("参数 parentId 引用不存在");
            }
            mutableChildren.computeIfAbsent(parameter.parentId(), ignored -> new ArrayList<>()).add(parameter);
        }
        if (!safeParameters.isEmpty() && roots.isEmpty()) {
            throw new IllegalArgumentException("parameters 必须包含顶层参数");
        }
        validateObjectChildren(roots);
        Map<String, List<HttpParameterDefinition>> children = new LinkedHashMap<>();
        mutableChildren.forEach((id, values) -> children.put(id, List.copyOf(values)));
        ParameterTree tree = new ParameterTree(
                List.copyOf(roots), Map.copyOf(byId), Map.copyOf(children)
        );
        Set<String> completed = new HashSet<>();
        for (HttpParameterDefinition root : roots) {
            validateNode(root, tree, new LinkedHashSet<>(), completed, 1);
        }
        if (completed.size() != safeParameters.size()) {
            throw new IllegalArgumentException("参数树包含循环引用或不可达节点");
        }
        return tree;
    }

    private void validateNode(
            HttpParameterDefinition parameter,
            ParameterTree tree,
            Set<String> visiting,
            Set<String> completed,
            int depth
    ) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("HTTP 参数嵌套深度超过安全上限");
        }
        if (!visiting.add(parameter.id())) {
            throw new IllegalArgumentException("参数 parentId 不能形成循环引用");
        }
        List<HttpParameterDefinition> children = tree.childrenOf(parameter.id());
        if (parameter.root()) {
            if (parameter.name().isBlank()) {
                throw new IllegalArgumentException("顶层参数 name 不能为空");
            }
            if (parameter.requestLocation() == null) {
                throw new IllegalArgumentException("顶层参数 requestLocation 不能为空");
            }
        } else if (parameter.requestLocation() != null) {
            throw new IllegalArgumentException("嵌套参数不能重复配置 requestLocation");
        }

        switch (parameter.dataType()) {
            case OBJECT -> validateObjectChildren(children);
            case ARRAY -> validateArrayChildren(children);
            default -> {
                if (!children.isEmpty()) {
                    throw new IllegalArgumentException("标量参数不能包含子节点");
                }
            }
        }
        validateConstraints(parameter);
        for (HttpParameterDefinition child : children) {
            validateNode(child, tree, visiting, completed, depth + 1);
        }
        visiting.remove(parameter.id());
        completed.add(parameter.id());
    }

    private static void validateObjectChildren(List<HttpParameterDefinition> children) {
        Set<String> names = new HashSet<>();
        for (HttpParameterDefinition child : children) {
            if (child.name().isBlank()) {
                throw new IllegalArgumentException("OBJECT 子参数 name 不能为空");
            }
            if (!names.add(child.name())) {
                throw new IllegalArgumentException("同一父节点下参数 name 不能重复");
            }
        }
    }

    private static void validateArrayChildren(List<HttpParameterDefinition> children) {
        if (children.size() != 1) {
            throw new IllegalArgumentException("ARRAY 参数必须有且只有一个匿名元素节点");
        }
        HttpParameterDefinition item = children.getFirst();
        if (!item.name().isBlank()) {
            throw new IllegalArgumentException("ARRAY 元素节点 name 必须为空");
        }
        if (item.required()) {
            throw new IllegalArgumentException("ARRAY 元素节点不能配置 required");
        }
        if (item.hasDefaultValue()) {
            throw new IllegalArgumentException("ARRAY 元素节点不能配置 defaultValue");
        }
    }

    private static void validateConstraints(HttpParameterDefinition parameter) {
        boolean hasStringConstraints = parameter.minLength() != null || parameter.maxLength() != null
                || !parameter.enumValues().isEmpty();
        boolean hasNumberConstraints = parameter.minimum() != null || parameter.maximum() != null;
        boolean hasArrayConstraints = parameter.minItems() != null || parameter.maxItems() != null
                || parameter.uniqueItems();
        if (hasStringConstraints && parameter.dataType() != HttpParameterDataType.STRING) {
            throw new IllegalArgumentException("字符串约束只能用于 STRING 参数");
        }
        if (hasNumberConstraints && parameter.dataType() != HttpParameterDataType.INTEGER
                && parameter.dataType() != HttpParameterDataType.NUMBER) {
            throw new IllegalArgumentException("数值约束只能用于 INTEGER 或 NUMBER 参数");
        }
        if (hasArrayConstraints && parameter.dataType() != HttpParameterDataType.ARRAY) {
            throw new IllegalArgumentException("数组约束只能用于 ARRAY 参数");
        }
    }

    private void validateLocations(ParameterTree tree, HttpToolMethod method, String urlTemplate) {
        Set<String> pathNames = new LinkedHashSet<>();
        boolean bodyRootFound = false;
        boolean bodyFound = false;
        for (HttpParameterDefinition root : tree.roots()) {
            HttpParameterLocation location = root.requestLocation();
            switch (location) {
                case PATH -> {
                    if (!root.required() || !root.dataType().scalar()) {
                        throw new IllegalArgumentException("PATH 参数必须为必填标量");
                    }
                    pathNames.add(root.name());
                }
                case QUERY -> {
                    if (!querySafe(root, tree)) {
                        throw new IllegalArgumentException("QUERY 只允许标量或标量数组");
                    }
                }
                case HEADER -> {
                    if (!root.dataType().scalar()) {
                        throw new IllegalArgumentException("HEADER 参数必须为标量");
                    }
                    if (FORBIDDEN_DYNAMIC_HEADERS.contains(root.name().toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("动态 Header 不允许覆盖敏感或逐跳请求头");
                    }
                }
                case BODY -> bodyFound = true;
                case BODY_ROOT -> {
                    if (bodyRootFound) {
                        throw new IllegalArgumentException("BODY_ROOT 参数只能配置一个");
                    }
                    bodyRootFound = true;
                }
            }
        }
        if (method == HttpToolMethod.GET && (bodyFound || bodyRootFound)) {
            throw new IllegalArgumentException("GET 工具不能配置 BODY 参数");
        }
        if (bodyFound && bodyRootFound) {
            throw new IllegalArgumentException("BODY_ROOT 不能与 BODY 参数同时配置");
        }
        if (!extractPathPlaceholders(urlTemplate).equals(pathNames)) {
            throw new IllegalArgumentException("PATH 参数必须完整匹配 URL 占位符");
        }
    }

    private static boolean querySafe(HttpParameterDefinition root, ParameterTree tree) {
        if (root.dataType().scalar()) {
            return true;
        }
        if (root.dataType() != HttpParameterDataType.ARRAY) {
            return false;
        }
        return tree.childrenOf(root.id()).getFirst().dataType().scalar();
    }

    private ObjectNode schemaFor(HttpParameterDefinition parameter, ParameterTree tree, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("HTTP 参数嵌套深度超过安全上限");
        }
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", parameter.dataType().schemaType());
        if (!parameter.description().isBlank()) schema.put("description", parameter.description());
        if (parameter.hasDefaultValue()) schema.set("default", parseValue(parameter.defaultValueJson(), "defaultValue"));
        if (parameter.hasExampleValue()) schema.putArray("examples")
                .add(parseValue(parameter.exampleValueJson(), "exampleValue"));
        if (!parameter.enumValues().isEmpty()) {
            ArrayNode values = schema.putArray("enum");
            parameter.enumValues().forEach(values::add);
        }
        if (parameter.minLength() != null) schema.put("minLength", parameter.minLength());
        if (parameter.maxLength() != null) schema.put("maxLength", parameter.maxLength());
        if (parameter.minimum() != null) schema.put("minimum", parameter.minimum());
        if (parameter.maximum() != null) schema.put("maximum", parameter.maximum());
        if (parameter.minItems() != null) schema.put("minItems", parameter.minItems());
        if (parameter.maxItems() != null) schema.put("maxItems", parameter.maxItems());
        if (parameter.uniqueItems()) schema.put("uniqueItems", true);

        List<HttpParameterDefinition> children = tree.childrenOf(parameter.id());
        if (parameter.dataType() == HttpParameterDataType.OBJECT) {
            ObjectNode properties = schema.putObject("properties");
            ArrayNode required = schema.putArray("required");
            for (HttpParameterDefinition child : children) {
                properties.set(child.name(), schemaFor(child, tree, depth + 1));
                if (child.required()) required.add(child.name());
            }
            if (required.isEmpty()) schema.remove("required");
            schema.put("additionalProperties", false);
        } else if (parameter.dataType() == HttpParameterDataType.ARRAY) {
            schema.set("items", schemaFor(children.getFirst(), tree, depth + 1));
        }
        validateConfiguredValue(parameter, schema);
        return schema;
    }

    private void validateConfiguredValue(HttpParameterDefinition parameter, ObjectNode schema) {
        if (parameter.hasDefaultValue()) {
            ensureType(parameter, schema.get("default"), "defaultValue");
        }
        if (parameter.hasExampleValue()) {
            ensureType(parameter, schema.path("examples").get(0), "exampleValue");
        }
    }

    private static void ensureType(HttpParameterDefinition parameter, JsonNode value, String fieldName) {
        boolean valid = switch (parameter.dataType()) {
            case STRING -> value.isTextual();
            case INTEGER -> value.isIntegralNumber();
            case NUMBER -> value.isNumber();
            case BOOLEAN -> value.isBoolean();
            case OBJECT -> value.isObject();
            case ARRAY -> value.isArray();
        };
        if (!valid) {
            throw new IllegalArgumentException(fieldName + " 与参数 dataType 不匹配");
        }
    }

    private JsonNode parseValue(String value, String fieldName) {
        try {
            JsonNode parsed = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(value);
            if (parsed == null || parsed.isNull()) {
                throw new IllegalArgumentException(fieldName + " 不能为 null");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(fieldName + " 必须是合法 JSON 值");
        }
    }

    private static Set<String> extractPathPlaceholders(String urlTemplate) {
        Set<String> placeholders = new LinkedHashSet<>();
        Matcher matcher = PATH_PLACEHOLDER.matcher(urlTemplate == null ? "" : urlTemplate);
        while (matcher.find()) {
            if (!placeholders.add(matcher.group(1))) {
                throw new IllegalArgumentException("URL PATH 占位符不能重复");
            }
        }
        return Set.copyOf(placeholders);
    }

    /**
     * 参数树的稳定只读索引。
     */
    record ParameterTree(
            List<HttpParameterDefinition> roots,
            Map<String, HttpParameterDefinition> byId,
            Map<String, List<HttpParameterDefinition>> children
    ) {
        List<HttpParameterDefinition> childrenOf(String id) {
            return children.getOrDefault(id, List.of());
        }
    }
}
