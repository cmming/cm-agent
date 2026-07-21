package com.cmagent.server.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 负责 HTTP 工具输入 Schema 的路径导航、局部投影、引用解析与类型域推导。
 */
final class HttpToolSchemaNavigator {
    private static final Set<ValueType> SCALAR_TYPES = Set.of(
            ValueType.STRING, ValueType.INTEGER, ValueType.NUMBER, ValueType.BOOLEAN
    );
    private static final Set<ValueType> ALL_TYPES = Set.copyOf(EnumSet.allOf(ValueType.class));

    private final ObjectMapper objectMapper;

    HttpToolSchemaNavigator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    JsonNode projectSourceSchema(JsonNode rootSchema, String sourcePointer) {
        Projection projection = projectSourceSchema(
                rootSchema,
                new SchemaNode(rootSchema, List.of()),
                HttpToolConfigValidator.pointerTokens(sourcePointer, "sourcePointer"),
                0,
                ALL_TYPES,
                new HashSet<>()
        );
        if (!projection.found()) {
            throw sourceMissing();
        }
        return projection.schema();
    }

    SchemaShape analyzeSourceShape(JsonNode rootSchema, String sourcePointer) {
        List<String> tokens = HttpToolConfigValidator.pointerTokens(sourcePointer, "sourcePointer");
        PathShape result = analyzePathShape(
                rootSchema, new SchemaNode(rootSchema, List.of()), tokens, 0, ALL_TYPES, new HashSet<>()
        );
        if (!result.found()) {
            throw sourceMissing();
        }
        return result.shape();
    }

    void validateTerminalLocalReferences(JsonNode rootSchema, String sourcePointer) {
        for (SchemaNode sourceNode : resolveSourceNodes(rootSchema, sourcePointer)) {
            validateTerminalLocalReferences(rootSchema, sourceNode, new LinkedHashSet<>(), new HashSet<>());
        }
    }

    private Projection projectSourceSchema(
            JsonNode rootSchema,
            SchemaNode current,
            List<String> tokens,
            int tokenIndex,
            Set<ValueType> allowedContainerTypes,
            Set<SourceTraversalKey> visited
    ) {
        if (tokenIndex == tokens.size()) {
            return new Projection(true, current.node().deepCopy());
        }
        SourceTraversalKey key = new SourceTraversalKey(current.schemaPath(), tokenIndex, allowedContainerTypes);
        if (!visited.add(key) || !current.node().isObject()) {
            return Projection.missing(objectMapper);
        }

        Set<ValueType> parentTypes = intersectTypes(
                withoutNull(analyzeShape(rootSchema, current, new HashSet<>()).types()), allowedContainerTypes
        );
        String token = tokens.get(tokenIndex);
        List<JsonNode> cumulative = new ArrayList<>();
        List<JsonNode> directAlternatives = new ArrayList<>();
        JsonNode properties = current.node().get("properties");
        if (parentTypes.contains(ValueType.OBJECT)
                && properties != null && properties.isObject() && properties.has(token)) {
            Projection property = projectSourceSchema(
                    rootSchema,
                    current.append("properties", properties).append(token, properties.get(token)),
                    tokens,
                    tokenIndex + 1,
                    ALL_TYPES,
                    new HashSet<>(visited)
            );
            if (property.found()) {
                directAlternatives.add(property.schema());
            }
        }
        if (parentTypes.contains(ValueType.ARRAY) && isArrayIndex(token)) {
            SchemaNode itemNode = resolveArrayItem(current, Integer.parseInt(token));
            if (itemNode != null) {
                Projection item = projectSourceSchema(
                        rootSchema, itemNode, tokens, tokenIndex + 1, ALL_TYPES, new HashSet<>(visited)
                );
                if (item.found()) {
                    directAlternatives.add(item.schema());
                }
            }
        }
        if (!directAlternatives.isEmpty()) {
            cumulative.add(alternativeSchema("anyOf", directAlternatives));
        }

        SchemaNode referenced = resolveLocalReference(rootSchema, current);
        if (referenced != null) {
            Projection reference = projectSourceSchema(
                    rootSchema, referenced, tokens, tokenIndex, parentTypes, new HashSet<>(visited)
            );
            if (reference.found()) {
                cumulative.add(reference.schema());
            }
        }
        JsonNode allOf = current.node().get("allOf");
        if (allOf != null && allOf.isArray()) {
            for (int index = 0; index < allOf.size(); index++) {
                Projection branch = projectSourceSchema(
                        rootSchema,
                        current.append("allOf", allOf).append(index, allOf.get(index)),
                        tokens,
                        tokenIndex,
                        parentTypes,
                        new HashSet<>(visited)
                );
                if (branch.found()) {
                    cumulative.add(branch.schema());
                }
            }
        }
        for (String keyword : List.of("anyOf", "oneOf")) {
            JsonNode branches = current.node().get(keyword);
            if (branches == null || !branches.isArray()) {
                continue;
            }
            List<Projection> projectedBranches = new ArrayList<>();
            boolean compositionFound = false;
            for (int index = 0; index < branches.size(); index++) {
                Projection branch = projectSourceSchema(
                        rootSchema,
                        current.append(keyword, branches).append(index, branches.get(index)),
                        tokens,
                        tokenIndex,
                        parentTypes,
                        new HashSet<>(visited)
                );
                projectedBranches.add(branch);
                compositionFound = compositionFound || branch.found();
            }
            if (compositionFound) {
                List<JsonNode> alternatives = projectedBranches.stream()
                        .map(branch -> branch.found()
                                ? branch.schema() : objectMapper.getNodeFactory().booleanNode(true))
                        .toList();
                cumulative.add(alternativeSchema(keyword, alternatives));
            }
        }
        if (cumulative.isEmpty()) {
            return Projection.missing(objectMapper);
        }
        return new Projection(true, cumulativeSchema(cumulative));
    }

    private JsonNode alternativeSchema(String keyword, List<JsonNode> alternatives) {
        if (alternatives.size() == 1) {
            return alternatives.getFirst();
        }
        ObjectNode schema = objectMapper.createObjectNode();
        ArrayNode branches = schema.putArray(keyword);
        alternatives.forEach(branches::add);
        return schema;
    }

    private JsonNode cumulativeSchema(List<JsonNode> constraints) {
        if (constraints.size() == 1) {
            return constraints.getFirst();
        }
        ObjectNode schema = objectMapper.createObjectNode();
        ArrayNode branches = schema.putArray("allOf");
        constraints.forEach(branches::add);
        return schema;
    }

    private PathShape analyzePathShape(
            JsonNode rootSchema,
            SchemaNode current,
            List<String> tokens,
            int tokenIndex,
            Set<ValueType> allowedContainerTypes,
            Set<SourceTraversalKey> visited
    ) {
        if (tokenIndex == tokens.size()) {
            return new PathShape(true, analyzeShape(rootSchema, current, new HashSet<>()));
        }
        SourceTraversalKey key = new SourceTraversalKey(current.schemaPath(), tokenIndex, allowedContainerTypes);
        if (!visited.add(key) || !current.node().isObject()) {
            return PathShape.missing();
        }

        String token = tokens.get(tokenIndex);
        SchemaShape constraints = SchemaShape.all();
        boolean found = false;
        Set<ValueType> parentTypes = intersectTypes(
                withoutNull(analyzeShape(rootSchema, current, new HashSet<>()).types()), allowedContainerTypes
        );
        SchemaShape directAlternatives = SchemaShape.empty();

        JsonNode properties = current.node().get("properties");
        if (parentTypes.contains(ValueType.OBJECT)
                && properties != null && properties.isObject() && properties.has(token)) {
            PathShape direct = analyzePathShape(
                    rootSchema,
                    current.append("properties", properties).append(token, properties.get(token)),
                    tokens,
                    tokenIndex + 1,
                    ALL_TYPES,
                    new HashSet<>(visited)
            );
            if (direct.found()) {
                directAlternatives = directAlternatives.union(direct.shape());
                found = true;
            }
        }
        if (parentTypes.contains(ValueType.ARRAY) && isArrayIndex(token)) {
            SchemaNode itemNode = resolveArrayItem(current, Integer.parseInt(token));
            if (itemNode != null) {
                PathShape direct = analyzePathShape(
                        rootSchema, itemNode, tokens, tokenIndex + 1, ALL_TYPES, new HashSet<>(visited)
                );
                if (direct.found()) {
                    directAlternatives = directAlternatives.union(direct.shape());
                    found = true;
                }
            }
        }
        if (found) {
            constraints = constraints.intersect(directAlternatives);
        }

        SchemaNode referenced = resolveLocalReference(rootSchema, current);
        if (referenced != null) {
            PathShape referencedShape = analyzePathShape(
                    rootSchema, referenced, tokens, tokenIndex, parentTypes, new HashSet<>(visited)
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
                        parentTypes,
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
                        parentTypes,
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
        List<String> tokens = HttpToolConfigValidator.pointerTokens(sourcePointer, "sourcePointer");
        List<SchemaNode> current = List.of(new SchemaNode(rootSchema, List.of()));
        for (String token : tokens) {
            Map<List<Object>, SchemaNode> next = new LinkedHashMap<>();
            for (SchemaNode node : current) {
                findChildSchemas(rootSchema, node, token, ALL_TYPES, new HashSet<>(), next);
            }
            if (next.isEmpty()) {
                throw sourceMissing();
            }
            current = List.copyOf(next.values());
        }
        return current;
    }

    private void findChildSchemas(
            JsonNode rootSchema,
            SchemaNode current,
            String token,
            Set<ValueType> allowedContainerTypes,
            Set<ContainerTraversalKey> visited,
            Map<List<Object>, SchemaNode> matches
    ) {
        ContainerTraversalKey key = new ContainerTraversalKey(current.schemaPath(), allowedContainerTypes);
        if (!visited.add(key) || !current.node().isObject()) {
            return;
        }
        Set<ValueType> parentTypes = intersectTypes(
                withoutNull(analyzeShape(rootSchema, current, new HashSet<>()).types()), allowedContainerTypes
        );
        JsonNode properties = current.node().get("properties");
        if (parentTypes.contains(ValueType.OBJECT)
                && properties != null && properties.isObject() && properties.has(token)) {
            SchemaNode property = current.append("properties", properties).append(token, properties.get(token));
            matches.put(property.schemaPath(), property);
        }
        if (parentTypes.contains(ValueType.ARRAY) && isArrayIndex(token)) {
            SchemaNode item = resolveArrayItem(current, Integer.parseInt(token));
            if (item != null) {
                matches.put(item.schemaPath(), item);
            }
        }
        SchemaNode referenced = resolveLocalReference(rootSchema, current);
        if (referenced != null) {
            findChildSchemas(rootSchema, referenced, token, parentTypes, visited, matches);
        }
        for (String keyword : List.of("allOf", "anyOf", "oneOf")) {
            JsonNode branches = current.node().get(keyword);
            if (branches == null || !branches.isArray()) {
                continue;
            }
            for (int index = 0; index < branches.size(); index++) {
                findChildSchemas(
                        rootSchema,
                        current.append(keyword, branches).append(index, branches.get(index)),
                        token,
                        parentTypes,
                        visited,
                        matches
                );
            }
        }
    }

    private SchemaNode resolveArrayItem(SchemaNode current, int itemIndex) {
        JsonNode prefixItems = current.node().get("prefixItems");
        if (prefixItems != null && prefixItems.isArray() && itemIndex < prefixItems.size()) {
            return current.append("prefixItems", prefixItems).append(itemIndex, prefixItems.get(itemIndex));
        }
        JsonNode items = current.node().get("items");
        if (items != null && (items.isObject() || items.isBoolean())) {
            return current.append("items", items);
        }
        return null;
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
            Set<ValueType> enumItemTypes = EnumSet.noneOf(ValueType.class);
            node.get("enum").forEach(value -> {
                enumTypes.add(typeOfValue(value));
                addArrayItemTypes(enumItemTypes, value);
            });
            result = result.intersect(new SchemaShape(enumTypes, enumItemTypes));
        }
        if (node.has("const")) {
            JsonNode constant = node.get("const");
            Set<ValueType> constantItemTypes = EnumSet.noneOf(ValueType.class);
            addArrayItemTypes(constantItemTypes, constant);
            result = result.intersect(new SchemaShape(Set.of(typeOfValue(constant)), constantItemTypes));
        }

        SchemaNode referenced = resolveLocalReference(rootSchema, schemaNode);
        if (referenced != null) {
            result = result.intersect(analyzeShape(rootSchema, referenced, new HashSet<>(visited)));
        }
        JsonNode allOf = node.get("allOf");
        if (allOf != null && allOf.isArray()) {
            for (int index = 0; index < allOf.size(); index++) {
                result = result.intersect(analyzeShape(
                        rootSchema,
                        schemaNode.append("allOf", allOf).append(index, allOf.get(index)),
                        new HashSet<>(visited)
                ));
            }
        }
        for (String keyword : List.of("anyOf", "oneOf")) {
            JsonNode branches = node.get(keyword);
            if (branches == null || !branches.isArray()) {
                continue;
            }
            SchemaShape alternatives = SchemaShape.empty();
            for (int index = 0; index < branches.size(); index++) {
                alternatives = alternatives.union(analyzeShape(
                        rootSchema,
                        schemaNode.append(keyword, branches).append(index, branches.get(index)),
                        new HashSet<>(visited)
                ));
            }
            result = result.intersect(alternatives);
        }
        JsonNode prefixItems = node.get("prefixItems");
        JsonNode items = node.get("items");
        if (prefixItems != null && prefixItems.isArray()) {
            Set<ValueType> itemTypes = EnumSet.noneOf(ValueType.class);
            for (int index = 0; index < prefixItems.size(); index++) {
                itemTypes.addAll(analyzeShape(
                        rootSchema,
                        schemaNode.append("prefixItems", prefixItems).append(index, prefixItems.get(index)),
                        new HashSet<>(visited)
                ).types());
            }
            if (items == null || items.isBoolean() && items.booleanValue()) {
                itemTypes.addAll(ALL_TYPES);
            } else if (items.isObject()) {
                itemTypes.addAll(analyzeShape(
                        rootSchema, schemaNode.append("items", items), new HashSet<>(visited)
                ).types());
            }
            result = result.intersect(new SchemaShape(ALL_TYPES, itemTypes));
        } else if (items != null && (items.isObject() || items.isBoolean())) {
            Set<ValueType> itemTypes = items.isBoolean()
                    ? items.booleanValue() ? ALL_TYPES : Set.of()
                    : analyzeShape(rootSchema, schemaNode.append("items", items), new HashSet<>(visited)).types();
            result = result.intersect(new SchemaShape(ALL_TYPES, itemTypes));
        }
        return result;
    }

    private SchemaNode resolveLocalReference(JsonNode rootSchema, SchemaNode schemaNode) {
        JsonNode referenceNode = schemaNode.node().get("$ref");
        if (referenceNode == null || !referenceNode.isTextual() || !referenceNode.asText().startsWith("#")) {
            return null;
        }
        List<String> tokens = HttpToolConfigValidator.pointerTokens(
                referenceNode.asText().substring(1), "$ref"
        );
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

    private void validateTerminalLocalReferences(
            JsonNode rootSchema,
            SchemaNode current,
            Set<List<Object>> activePaths,
            Set<List<Object>> validatedPaths
    ) {
        if (!current.node().isObject() || validatedPaths.contains(current.schemaPath())) {
            return;
        }
        if (!activePaths.add(current.schemaPath())) {
            throw new IllegalArgumentException("JSON Schema 本地引用存在循环");
        }
        JsonNode referenceNode = current.node().get("$ref");
        if (referenceNode != null && referenceNode.isTextual() && referenceNode.asText().startsWith("#")) {
            SchemaNode referenced = resolveRequiredLocalReference(rootSchema, referenceNode.asText());
            if (activePaths.contains(referenced.schemaPath())) {
                throw new IllegalArgumentException("JSON Schema 本地引用存在循环");
            }
            validateTerminalLocalReferences(
                    rootSchema, referenced, new LinkedHashSet<>(activePaths), validatedPaths
            );
        }
        for (String keyword : List.of("allOf", "anyOf", "oneOf")) {
            JsonNode branches = current.node().get(keyword);
            if (branches == null || !branches.isArray()) {
                continue;
            }
            for (int index = 0; index < branches.size(); index++) {
                validateTerminalLocalReferences(
                        rootSchema,
                        current.append(keyword, branches).append(index, branches.get(index)),
                        new LinkedHashSet<>(activePaths),
                        validatedPaths
                );
            }
        }
        validatedPaths.add(current.schemaPath());
    }

    private SchemaNode resolveRequiredLocalReference(JsonNode rootSchema, String reference) {
        final List<String> tokens;
        try {
            tokens = HttpToolConfigValidator.pointerTokens(reference.substring(1), "$ref");
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("JSON Schema 本地引用无效");
        }
        JsonNode current = rootSchema;
        List<Object> schemaPath = new ArrayList<>();
        for (String token : tokens) {
            if (current.isArray() && isArrayIndex(token)) {
                int index = Integer.parseInt(token);
                if (index >= current.size()) {
                    throw new IllegalArgumentException("JSON Schema 本地引用无效");
                }
                current = current.get(index);
                schemaPath.add(index);
            } else if (current.isObject() && current.has(token)) {
                current = current.get(token);
                schemaPath.add(token);
            } else {
                throw new IllegalArgumentException("JSON Schema 本地引用无效");
            }
        }
        return new SchemaNode(current, schemaPath);
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

    private static void addArrayItemTypes(Set<ValueType> itemTypes, JsonNode value) {
        if (value.isArray()) {
            value.forEach(item -> itemTypes.add(typeOfValue(item)));
        }
    }

    private static Set<ValueType> withoutNull(Set<ValueType> types) {
        Set<ValueType> result = EnumSet.noneOf(ValueType.class);
        result.addAll(types);
        result.remove(ValueType.NULL);
        return Set.copyOf(result);
    }

    private static Set<ValueType> intersectTypes(Set<ValueType> left, Set<ValueType> right) {
        Set<ValueType> result = EnumSet.noneOf(ValueType.class);
        result.addAll(left);
        result.retainAll(right);
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

    private static IllegalArgumentException sourceMissing() {
        return new IllegalArgumentException("sourcePointer 未指向 Schema 中存在的输入节点");
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

    record SchemaShape(Set<ValueType> types, Set<ValueType> arrayItemTypes) {
        SchemaShape {
            types = Set.copyOf(types);
            arrayItemTypes = Set.copyOf(arrayItemTypes);
        }

        boolean hasNoKnownNonNullType() {
            return withoutNull(types).isEmpty();
        }

        boolean isOnlyArray() {
            return withoutNull(types).equals(Set.of(ValueType.ARRAY));
        }

        boolean hasArrayAlternative() {
            return withoutNull(types).contains(ValueType.ARRAY);
        }

        boolean isScalarSafe() {
            return SCALAR_TYPES.containsAll(withoutNull(types));
        }

        boolean isQuerySafe() {
            Set<ValueType> possibleTypes = withoutNull(types);
            Set<ValueType> itemTypes = withoutNull(arrayItemTypes);
            for (ValueType type : possibleTypes) {
                if (SCALAR_TYPES.contains(type)) {
                    continue;
                }
                if (type != ValueType.ARRAY || itemTypes.isEmpty() || !SCALAR_TYPES.containsAll(itemTypes)) {
                    return false;
                }
            }
            return true;
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

    private record SchemaNode(JsonNode node, List<Object> schemaPath) {
        private SchemaNode {
            schemaPath = List.copyOf(schemaPath);
        }

        private SchemaNode append(Object pathElement, JsonNode child) {
            List<Object> childPath = new ArrayList<>(schemaPath);
            childPath.add(pathElement);
            return new SchemaNode(child, childPath);
        }
    }

    private record SourceTraversalKey(
            List<Object> schemaPath,
            int tokenIndex,
            Set<ValueType> allowedContainerTypes
    ) {
        private SourceTraversalKey {
            schemaPath = List.copyOf(schemaPath);
            allowedContainerTypes = Set.copyOf(allowedContainerTypes);
        }
    }

    private record ContainerTraversalKey(List<Object> schemaPath, Set<ValueType> allowedContainerTypes) {
        private ContainerTraversalKey {
            schemaPath = List.copyOf(schemaPath);
            allowedContainerTypes = Set.copyOf(allowedContainerTypes);
        }
    }

    private record Projection(boolean found, JsonNode schema) {
        private static Projection missing(ObjectMapper objectMapper) {
            return new Projection(false, objectMapper.getNodeFactory().booleanNode(true));
        }
    }

    private record PathShape(boolean found, SchemaShape shape) {
        private static PathShape missing() {
            return new PathShape(false, SchemaShape.all());
        }
    }
}
