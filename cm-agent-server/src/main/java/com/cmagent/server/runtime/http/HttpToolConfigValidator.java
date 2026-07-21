package com.cmagent.server.runtime.http;

import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolConfig;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InvalidSchemaRefException;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaException;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;
import com.networknt.schema.path.PathType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
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
public class HttpToolConfigValidator {
    private static final Pattern PATH_PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
    private static final Set<String> FORBIDDEN_DYNAMIC_HEADERS = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "authorization", "cookie",
            "proxy-authorization", "upgrade"
    );
    private static final Set<ValueType> SCALAR_TYPES = Set.of(
            ValueType.STRING, ValueType.INTEGER, ValueType.NUMBER, ValueType.BOOLEAN
    );
    private static final Set<ValueType> ALL_TYPES = Set.copyOf(EnumSet.allOf(ValueType.class));

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
        JsonNode rootSchema = parseJsonStrict(config.inputSchema(), "inputSchema 不是合法 JSON");
        validateSchemaDocument(rootSchema);
        validateMappings(config, rootSchema, compile(rootSchema));
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
        SchemaShape shape = analyzeSourceShape(rootSchema, sourcePointer);
        Set<ValueType> nonNullTypes = withoutNull(shape.types());
        if (nonNullTypes.isEmpty()) {
            throw new IllegalArgumentException("sourcePointer 对应 Schema 类型不明确");
        }
        if (nonNullTypes.equals(Set.of(ValueType.ARRAY))) {
            return true;
        }
        if (nonNullTypes.contains(ValueType.ARRAY)) {
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

    private void validateMappings(HttpToolConfig config, JsonNode rootSchema, Schema compiledRoot) {
        Set<String> targets = new HashSet<>();
        List<List<String>> bodyTargets = new ArrayList<>();
        Map<List<String>, ContainerShape> bodyContainerShapes = new LinkedHashMap<>();
        Set<String> pathTargets = new LinkedHashSet<>();

        for (HttpParameterMapping mapping : config.parameterMappings()) {
            List<SchemaNode> sourceNodes = resolveSourceNodes(rootSchema, mapping.sourcePointer());
            validateDefault(mapping, compiledRoot, sourceNodes);
            validateLocationType(mapping, rootSchema);
            validateTarget(mapping, targets, bodyTargets, bodyContainerShapes, pathTargets);
        }

        if (!extractPathPlaceholders(config.urlTemplate()).equals(pathTargets)) {
            throw new IllegalArgumentException("PATH 映射必须完整匹配 URL 占位符");
        }
    }

    private void validateDefault(
            HttpParameterMapping mapping,
            Schema compiledRoot,
            List<SchemaNode> sourceNodes
    ) {
        if (!mapping.hasDefaultValue()) {
            return;
        }
        JsonNode defaultValue = parseJsonStrict(
                mapping.defaultValueJson(), "defaultValueJson 必须是合法 JSON 值"
        );
        if (defaultValue.isNull()) {
            throw new IllegalArgumentException("defaultValueJson 不能为 null");
        }
        for (SchemaNode sourceNode : sourceNodes) {
            Schema subSchema = sourceNode.schemaPath().isEmpty()
                    ? compiledRoot : compiledRoot.getSubSchema(sourceNode.toNodePath());
            if (subSchema == null || !subSchema.validate(defaultValue).isEmpty()) {
                throw new IllegalArgumentException("defaultValueJson 不符合对应 Schema");
            }
        }
    }

    private void validateLocationType(
            HttpParameterMapping mapping,
            JsonNode rootSchema
    ) {
        if (mapping.location() == HttpParameterLocation.BODY) {
            return;
        }
        SchemaShape shape = analyzeSourceShape(rootSchema, mapping.sourcePointer());
        Set<ValueType> possibleTypes = withoutNull(shape.types());
        if (possibleTypes.isEmpty()) {
            throw new IllegalArgumentException("sourcePointer 对应 Schema 类型不明确");
        }
        if (mapping.location() == HttpParameterLocation.QUERY) {
            if (!querySafe(possibleTypes, shape.arrayItemTypes())) {
                throw new IllegalArgumentException("QUERY 只允许标量或标量数组，object/array 参数只允许映射到 BODY");
            }
        } else if (!SCALAR_TYPES.containsAll(possibleTypes)) {
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
            ContainerShape required = isArrayIndex(target.get(index + 1))
                    ? ContainerShape.ARRAY : ContainerShape.OBJECT;
            ContainerShape existing = bodyContainerShapes.putIfAbsent(prefix, required);
            if (existing != null && existing != required) {
                throw new IllegalArgumentException("BODY targetPointer 中间容器类型冲突");
            }
        }
    }

    private SchemaShape analyzeSourceShape(JsonNode rootSchema, String sourcePointer) {
        List<String> tokens = pointerTokens(sourcePointer, "sourcePointer");
        PathShape result = analyzePathShape(
                rootSchema, new SchemaNode(rootSchema, List.of()), tokens, 0, new HashSet<>()
        );
        if (!result.found()) {
            throw new IllegalArgumentException("sourcePointer 未指向 Schema 中存在的输入节点");
        }
        return result.shape();
    }

    private PathShape analyzePathShape(
            JsonNode rootSchema,
            SchemaNode current,
            List<String> tokens,
            int tokenIndex,
            Set<SourceTraversalKey> visited
    ) {
        if (tokenIndex == tokens.size()) {
            return new PathShape(true, analyzeShape(rootSchema, current, new HashSet<>()));
        }
        SourceTraversalKey key = new SourceTraversalKey(current.schemaPath(), tokenIndex);
        if (!visited.add(key) || !current.node().isObject()) {
            return PathShape.missing();
        }

        String token = tokens.get(tokenIndex);
        SchemaShape constraints = SchemaShape.all();
        boolean found = false;

        JsonNode properties = current.node().get("properties");
        if (properties != null && properties.isObject() && properties.has(token)) {
            PathShape direct = analyzePathShape(
                    rootSchema,
                    current.append("properties", properties).append(token, properties.get(token)),
                    tokens,
                    tokenIndex + 1,
                    new HashSet<>(visited)
            );
            constraints = constraints.intersect(direct.shape());
            found = direct.found();
        }
        if (isArrayIndex(token)) {
            int itemIndex = Integer.parseInt(token);
            JsonNode prefixItems = current.node().get("prefixItems");
            SchemaNode itemNode = null;
            if (prefixItems != null && prefixItems.isArray() && itemIndex < prefixItems.size()) {
                itemNode = current.append("prefixItems", prefixItems).append(itemIndex, prefixItems.get(itemIndex));
            } else {
                JsonNode items = current.node().get("items");
                if (items != null && (items.isObject() || items.isBoolean())) {
                    itemNode = current.append("items", items);
                }
            }
            if (itemNode != null) {
                PathShape direct = analyzePathShape(
                        rootSchema, itemNode, tokens, tokenIndex + 1, new HashSet<>(visited)
                );
                constraints = constraints.intersect(direct.shape());
                found = found || direct.found();
            }
        }

        SchemaNode referenced = resolveLocalReference(rootSchema, current);
        if (referenced != null) {
            PathShape referencedShape = analyzePathShape(
                    rootSchema, referenced, tokens, tokenIndex, new HashSet<>(visited)
            );
            if (referencedShape.found()) {
                constraints = constraints.intersect(referencedShape.shape());
                found = true;
            }
        }

        JsonNode allOf = current.node().get("allOf");
        if (allOf != null && allOf.isArray()) {
            for (int index = 0; index < allOf.size(); index++) {
                PathShape branch = analyzePathShape(
                        rootSchema,
                        current.append("allOf", allOf).append(index, allOf.get(index)),
                        tokens,
                        tokenIndex,
                        new HashSet<>(visited)
                );
                if (branch.found()) {
                    constraints = constraints.intersect(branch.shape());
                    found = true;
                }
            }
        }

        for (String keyword : List.of("anyOf", "oneOf")) {
            JsonNode branches = current.node().get(keyword);
            if (branches == null || !branches.isArray()) {
                continue;
            }
            List<PathShape> branchShapes = new ArrayList<>();
            boolean compositionFound = false;
            for (int index = 0; index < branches.size(); index++) {
                PathShape branch = analyzePathShape(
                        rootSchema,
                        current.append(keyword, branches).append(index, branches.get(index)),
                        tokens,
                        tokenIndex,
                        new HashSet<>(visited)
                );
                branchShapes.add(branch);
                compositionFound = compositionFound || branch.found();
            }
            if (compositionFound) {
                SchemaShape alternatives = SchemaShape.empty();
                for (PathShape branch : branchShapes) {
                    alternatives = alternatives.union(branch.found() ? branch.shape() : SchemaShape.all());
                }
                constraints = constraints.intersect(alternatives);
                found = true;
            }
        }
        return new PathShape(found, constraints);
    }

    private List<SchemaNode> resolveSourceNodes(JsonNode rootSchema, String sourcePointer) {
        List<String> tokens = pointerTokens(sourcePointer, "sourcePointer");
        List<SchemaNode> current = List.of(new SchemaNode(rootSchema, List.of()));
        for (String token : tokens) {
            Map<List<Object>, SchemaNode> next = new LinkedHashMap<>();
            for (SchemaNode node : current) {
                findChildSchemas(rootSchema, node, token, new HashSet<>(), next);
            }
            if (next.isEmpty()) {
                throw new IllegalArgumentException("sourcePointer 未指向 Schema 中存在的输入节点");
            }
            current = List.copyOf(next.values());
        }
        return current;
    }

    private void findChildSchemas(
            JsonNode rootSchema,
            SchemaNode current,
            String token,
            Set<List<Object>> visited,
            Map<List<Object>, SchemaNode> matches
    ) {
        if (!visited.add(current.schemaPath()) || !current.node().isObject()) {
            return;
        }
        JsonNode properties = current.node().get("properties");
        if (properties != null && properties.isObject() && properties.has(token)) {
            SchemaNode property = current.append("properties", properties).append(token, properties.get(token));
            matches.put(property.schemaPath(), property);
        }
        if (isArrayIndex(token)) {
            int itemIndex = Integer.parseInt(token);
            JsonNode prefixItems = current.node().get("prefixItems");
            if (prefixItems != null && prefixItems.isArray() && itemIndex < prefixItems.size()) {
                SchemaNode item = current.append("prefixItems", prefixItems).append(itemIndex, prefixItems.get(itemIndex));
                matches.put(item.schemaPath(), item);
            } else {
                JsonNode items = current.node().get("items");
                if (items != null && (items.isObject() || items.isBoolean())) {
                    SchemaNode item = current.append("items", items);
                    matches.put(item.schemaPath(), item);
                }
            }
        }
        SchemaNode referenced = resolveLocalReference(rootSchema, current);
        if (referenced != null) {
            findChildSchemas(rootSchema, referenced, token, visited, matches);
        }
        for (String keyword : List.of("allOf", "anyOf", "oneOf")) {
            JsonNode branches = current.node().get(keyword);
            if (branches == null || !branches.isArray()) {
                continue;
            }
            for (int index = 0; index < branches.size(); index++) {
                findChildSchemas(rootSchema,
                        current.append(keyword, branches).append(index, branches.get(index)), token, visited, matches);
            }
        }
    }

    private SchemaShape analyzeShape(JsonNode rootSchema, SchemaNode schemaNode, Set<List<Object>> visited) {
        if (!visited.add(schemaNode.schemaPath())) {
            return SchemaShape.all();
        }
        JsonNode node = schemaNode.node();
        if (node.isBoolean()) {
            return node.booleanValue() ? SchemaShape.all() : SchemaShape.empty();
        }
        if (!node.isObject()) {
            return SchemaShape.all();
        }

        SchemaShape result = SchemaShape.all();
        if (node.has("type")) {
            result = result.intersect(new SchemaShape(typesFromType(node.get("type")), ALL_TYPES));
        }
        if (node.has("enum") && node.get("enum").isArray()) {
            Set<ValueType> enumTypes = EnumSet.noneOf(ValueType.class);
            node.get("enum").forEach(value -> enumTypes.add(typeOfValue(value)));
            result = result.intersect(new SchemaShape(enumTypes, ALL_TYPES));
        }
        if (node.has("const")) {
            result = result.intersect(new SchemaShape(Set.of(typeOfValue(node.get("const"))), ALL_TYPES));
        }

        SchemaNode referenced = resolveLocalReference(rootSchema, schemaNode);
        if (referenced != null) {
            result = result.intersect(analyzeShape(rootSchema, referenced, new HashSet<>(visited)));
        }
        JsonNode allOf = node.get("allOf");
        if (allOf != null && allOf.isArray()) {
            for (int index = 0; index < allOf.size(); index++) {
                result = result.intersect(analyzeShape(rootSchema,
                        schemaNode.append("allOf", allOf).append(index, allOf.get(index)),
                        new HashSet<>(visited)));
            }
        }
        for (String keyword : List.of("anyOf", "oneOf")) {
            JsonNode branches = node.get(keyword);
            if (branches == null || !branches.isArray()) {
                continue;
            }
            SchemaShape alternatives = SchemaShape.empty();
            for (int index = 0; index < branches.size(); index++) {
                alternatives = alternatives.union(analyzeShape(rootSchema,
                        schemaNode.append(keyword, branches).append(index, branches.get(index)),
                        new HashSet<>(visited)));
            }
            result = result.intersect(alternatives);
        }
        JsonNode items = node.get("items");
        if (items != null && (items.isObject() || items.isBoolean())) {
            SchemaShape itemShape = analyzeShape(
                    rootSchema, schemaNode.append("items", items), new HashSet<>(visited)
            );
            result = result.intersect(new SchemaShape(ALL_TYPES, itemShape.types()));
        }
        return result;
    }

    private SchemaNode resolveLocalReference(JsonNode rootSchema, SchemaNode schemaNode) {
        JsonNode referenceNode = schemaNode.node().get("$ref");
        if (referenceNode == null || !referenceNode.isTextual()) {
            return null;
        }
        String reference = referenceNode.asText();
        if (!reference.startsWith("#")) {
            return null;
        }
        String fragment = reference.substring(1);
        List<String> tokens = pointerTokens(fragment, "$ref");
        JsonNode current = rootSchema;
        List<Object> schemaPath = new ArrayList<>();
        for (String token : tokens) {
            if (current.isArray()) {
                if (!isArrayIndex(token)) {
                    return null;
                }
                int index = Integer.parseInt(token);
                if (index >= current.size()) {
                    return null;
                }
                current = current.get(index);
                schemaPath.add(index);
            } else if (current.isObject() && current.has(token)) {
                current = current.get(token);
                schemaPath.add(token);
            } else {
                return null;
            }
        }
        return new SchemaNode(current, schemaPath);
    }

    private static boolean querySafe(Set<ValueType> possibleTypes, Set<ValueType> itemTypes) {
        for (ValueType type : possibleTypes) {
            if (SCALAR_TYPES.contains(type)) {
                continue;
            }
            if (type != ValueType.ARRAY || !SCALAR_TYPES.containsAll(withoutNull(itemTypes))) {
                return false;
            }
        }
        return true;
    }

    private static Set<ValueType> typesFromType(JsonNode typeNode) {
        Set<ValueType> types = EnumSet.noneOf(ValueType.class);
        if (typeNode.isTextual()) {
            addDeclaredType(types, typeNode.asText());
        } else if (typeNode.isArray()) {
            typeNode.forEach(item -> {
                if (item.isTextual()) {
                    addDeclaredType(types, item.asText());
                }
            });
        }
        return Set.copyOf(types);
    }

    private static void addDeclaredType(Set<ValueType> types, String declaredType) {
        switch (declaredType) {
            case "string" -> types.add(ValueType.STRING);
            case "integer" -> types.add(ValueType.INTEGER);
            case "number" -> {
                types.add(ValueType.INTEGER);
                types.add(ValueType.NUMBER);
            }
            case "boolean" -> types.add(ValueType.BOOLEAN);
            case "object" -> types.add(ValueType.OBJECT);
            case "array" -> types.add(ValueType.ARRAY);
            case "null" -> types.add(ValueType.NULL);
            default -> {
            }
        }
    }

    private static ValueType typeOfValue(JsonNode value) {
        if (value.isTextual()) {
            return ValueType.STRING;
        }
        if (value.isIntegralNumber()) {
            return ValueType.INTEGER;
        }
        if (value.isNumber()) {
            return ValueType.NUMBER;
        }
        if (value.isBoolean()) {
            return ValueType.BOOLEAN;
        }
        if (value.isObject()) {
            return ValueType.OBJECT;
        }
        if (value.isArray()) {
            return ValueType.ARRAY;
        }
        return ValueType.NULL;
    }

    private static Set<ValueType> withoutNull(Set<ValueType> types) {
        Set<ValueType> result = EnumSet.noneOf(ValueType.class);
        result.addAll(types);
        result.remove(ValueType.NULL);
        return Set.copyOf(result);
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

    private enum ContainerShape {
        OBJECT,
        ARRAY
    }

    private enum ValueType {
        STRING,
        INTEGER,
        NUMBER,
        BOOLEAN,
        OBJECT,
        ARRAY,
        NULL
    }

    private record SchemaNode(JsonNode node, List<Object> schemaPath) {
        private SchemaNode {
            schemaPath = List.copyOf(schemaPath);
        }

        private SchemaNode append(Object pathElement, JsonNode child) {
            List<Object> childPath = new ArrayList<>(schemaPath);
            childPath.add(pathElement);
            return new SchemaNode(child, childPath);
        }

        private NodePath toNodePath() {
            NodePath path = new NodePath(PathType.JSON_POINTER);
            for (Object element : schemaPath) {
                path = element instanceof Integer index ? path.append(index) : path.append(element.toString());
            }
            return path;
        }
    }

    private record SourceTraversalKey(List<Object> schemaPath, int tokenIndex) {
        private SourceTraversalKey {
            schemaPath = List.copyOf(schemaPath);
        }
    }

    private record PathShape(boolean found, SchemaShape shape) {
        private static PathShape missing() {
            return new PathShape(false, SchemaShape.all());
        }
    }

    private record SchemaShape(Set<ValueType> types, Set<ValueType> arrayItemTypes) {
        private SchemaShape {
            types = Set.copyOf(types);
            arrayItemTypes = Set.copyOf(arrayItemTypes);
        }

        private static SchemaShape all() {
            return new SchemaShape(ALL_TYPES, ALL_TYPES);
        }

        private static SchemaShape empty() {
            return new SchemaShape(Set.of(), Set.of());
        }

        private SchemaShape intersect(SchemaShape other) {
            Set<ValueType> intersectedTypes = EnumSet.noneOf(ValueType.class);
            intersectedTypes.addAll(types);
            intersectedTypes.retainAll(other.types);
            Set<ValueType> intersectedItems = EnumSet.noneOf(ValueType.class);
            intersectedItems.addAll(arrayItemTypes);
            intersectedItems.retainAll(other.arrayItemTypes);
            return new SchemaShape(intersectedTypes, intersectedItems);
        }

        private SchemaShape union(SchemaShape other) {
            Set<ValueType> unionTypes = EnumSet.noneOf(ValueType.class);
            unionTypes.addAll(types);
            unionTypes.addAll(other.types);
            Set<ValueType> unionItems = EnumSet.noneOf(ValueType.class);
            if (types.contains(ValueType.ARRAY)) {
                unionItems.addAll(arrayItemTypes);
            }
            if (other.types.contains(ValueType.ARRAY)) {
                unionItems.addAll(other.arrayItemTypes);
            }
            return new SchemaShape(unionTypes, unionItems);
        }
    }
}
