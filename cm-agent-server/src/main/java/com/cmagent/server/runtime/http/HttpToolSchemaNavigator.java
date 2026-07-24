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

/**
 * 在 JSON Schema 中解析嵌套对象、数组和 required 约束。
 */
final class HttpToolSchemaNavigator {
    private static final int MAX_REFERENCE_TRAVERSAL_DEPTH = 256;
    private static final int MAX_REFERENCE_TRAVERSAL_STATES = 10_000;
    private static final Set<ValueType> SCALAR_TYPES = Set.of(
            ValueType.STRING, ValueType.INTEGER, ValueType.NUMBER, ValueType.BOOLEAN
    );
    private static final Set<ValueType> ALL_TYPES = Set.copyOf(EnumSet.allOf(ValueType.class));

    private final ObjectMapper objectMapper;
    /**
     * HttpToolSchemaNavigator：处理该类内部的业务逻辑或辅助计算。
     */
    HttpToolSchemaNavigator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }
    /**
     * projectSourceSchema：转换内部数据为目标表示。
     */
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
    /**
     * analyzeSourceShape：处理该类内部的业务逻辑或辅助计算。
     */
    SchemaShape analyzeSourceShape(JsonNode rootSchema, String sourcePointer) {
        List<String> tokens = HttpToolConfigValidator.pointerTokens(sourcePointer, "sourcePointer");
        PathShape result = analyzePathShape(
                /**
                 * SchemaNode：处理该类内部的业务逻辑或辅助计算。
                 */
                rootSchema, new SchemaNode(rootSchema, List.of()), tokens, 0, ALL_TYPES, new HashSet<>()
        );
        if (!result.found()) {
            throw sourceMissing();
        }
        return result.shape();
    }
    /**
     * validateTerminalLocalReferences：校验输入、状态或前置条件。
     */
    void validateTerminalLocalReferences(JsonNode rootSchema, List<String> sourcePointers) {
        ReferenceTraversalContext context = new ReferenceTraversalContext();
        for (String sourcePointer : sourcePointers) {
            for (SchemaNode sourceNode : resolveSourceNodes(rootSchema, sourcePointer)) {
                validateTerminalLocalReferences(
                        rootSchema, sourceNode, new LinkedHashSet<>(), context
                );
            }
        }
    }

    /**
     * projectSourceSchema：转换内部数据为目标表示。
     */
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
        HttpToolArrayIndex.ParseResult arrayIndex = parseArrayIndexForParent(parentTypes, token);
        if (arrayIndex.isValid()) {
            SchemaNode itemNode = resolveArrayItem(current, arrayIndex.requireValue());
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
                        .filter(Projection::found)
                        .map(Projection::schema)
                        .toList();
                cumulative.add(alternativeSchema("anyOf", alternatives));
            }
        }
        if (cumulative.isEmpty()) {
            return Projection.missing(objectMapper);
        }
        return new Projection(true, cumulativeSchema(cumulative));
    }

    /**
     * alternativeSchema：处理该类内部的业务逻辑或辅助计算。
     */
    private JsonNode alternativeSchema(String keyword, List<JsonNode> alternatives) {
        if (alternatives.size() == 1) {
            return alternatives.getFirst();
        }
        ObjectNode schema = objectMapper.createObjectNode();
        ArrayNode branches = schema.putArray(keyword);
        alternatives.forEach(branches::add);
        return schema;
    }

    /**
     * cumulativeSchema：处理该类内部的业务逻辑或辅助计算。
     */
    private JsonNode cumulativeSchema(List<JsonNode> constraints) {
        if (constraints.size() == 1) {
            return constraints.getFirst();
        }
        ObjectNode schema = objectMapper.createObjectNode();
        ArrayNode branches = schema.putArray("allOf");
        constraints.forEach(branches::add);
        return schema;
    }

    /**
     * analyzePathShape：处理该类内部的业务逻辑或辅助计算。
     */
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
        HttpToolArrayIndex.ParseResult arrayIndex = parseArrayIndexForParent(parentTypes, token);
        if (arrayIndex.isValid()) {
            SchemaNode itemNode = resolveArrayItem(current, arrayIndex.requireValue());
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

    /**
     * resolveSourceNodes：解析并定位可用的目标对象。
     */
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

    /**
     * findChildSchemas：查询并返回当前上下文中的匹配结果。
     */
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
        HttpToolArrayIndex.ParseResult arrayIndex = parseArrayIndexForParent(parentTypes, token);
        if (arrayIndex.isValid()) {
            SchemaNode item = resolveArrayItem(current, arrayIndex.requireValue());
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

    /**
     * resolveArrayItem：解析并定位可用的目标对象。
     */
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

    /**
     * parseArrayIndexForParent：读取并解析输入内容。
     */
    private static HttpToolArrayIndex.ParseResult parseArrayIndexForParent(
            Set<ValueType> parentTypes,
            String token
    ) {
        if (!parentTypes.contains(ValueType.ARRAY)) {
            return HttpToolArrayIndex.ParseResult.nonNumeric();
        }
        HttpToolArrayIndex.ParseResult result = HttpToolArrayIndex.parse(token);
        if (result.isInvalid()) {
            throw HttpToolArrayIndex.invalidException();
        }
        return result;
    }

    /**
     * analyzeShape：处理该类内部的业务逻辑或辅助计算。
     */
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

    /**
     * resolveLocalReference：解析并定位可用的目标对象。
     */
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
                HttpToolArrayIndex.ParseResult result = HttpToolArrayIndex.parse(token);
                if (!result.isValid()) {
                    return null;
                }
                int index = result.requireValue();
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

    /**
     * validateTerminalLocalReferences：校验输入、状态或前置条件。
     */
    private void validateTerminalLocalReferences(
            JsonNode rootSchema,
            SchemaNode current,
            Set<List<Object>> directReferenceChain,
            ReferenceTraversalContext context
    ) {
        if (!current.node().isObject()) {
            return;
        }
        ReferenceTraversalState state = new ReferenceTraversalState(
                current.schemaPath(), directReferenceChain
        );
        if (context.isCompleted(state)) {
            return;
        }
        if (!context.enter(current.schemaPath())) {
            return;
        }
        boolean completed = false;
        try {
            context.enterTraversalDepth();
            context.recordTraversalState();
            JsonNode referenceNode = current.node().get("$ref");
            if (referenceNode != null && referenceNode.isTextual() && referenceNode.asText().startsWith("#")) {
                SchemaNode referenced = resolveRequiredLocalReference(rootSchema, referenceNode.asText());
                Set<List<Object>> nextReferenceChain = new LinkedHashSet<>(directReferenceChain);
                if (nextReferenceChain.contains(referenced.schemaPath())) {
                    throw new IllegalArgumentException("JSON Schema 本地引用存在循环");
                }
                nextReferenceChain.add(referenced.schemaPath());
                validateTerminalLocalReferences(
                        rootSchema, referenced, nextReferenceChain, context
                );
            }

            for (String keyword : List.of(
                    "properties", "patternProperties", "$defs", "definitions"
            )) {
                validateSchemaMapChildren(rootSchema, current, keyword, Set.of(), context);
            }
            for (String keyword : List.of(
                    "additionalProperties", "unevaluatedProperties", "propertyNames", "items", "contains"
            )) {
                validateSchemaChild(
                        rootSchema, current, keyword, Set.of(), context
                );
            }
            Set<List<Object>> sameInstanceReferenceChain = new LinkedHashSet<>(directReferenceChain);
            sameInstanceReferenceChain.add(current.schemaPath());
            validateSchemaMapChildren(
                    rootSchema, current, "dependentSchemas", sameInstanceReferenceChain, context
            );
            for (String keyword : List.of("not", "if", "then", "else")) {
                validateSchemaChild(
                        rootSchema, current, keyword, sameInstanceReferenceChain, context
                );
            }
            validateSchemaArrayChildren(
                    rootSchema, current, "prefixItems", Set.of(), context
            );
            for (String keyword : List.of("allOf", "anyOf", "oneOf")) {
                validateSchemaArrayChildren(
                        rootSchema, current, keyword, sameInstanceReferenceChain, context
                );
            }
            completed = true;
        } finally {
            context.exitTraversalDepth();
            context.exit(current.schemaPath());
            if (completed) {
                context.complete(state);
            }
        }
    }

    /**
     * validateSchemaMapChildren：校验输入、状态或前置条件。
     */
    private void validateSchemaMapChildren(
            JsonNode rootSchema,
            SchemaNode current,
            String keyword,
            Set<List<Object>> directReferenceChain,
            ReferenceTraversalContext context
    ) {
        JsonNode children = current.node().get(keyword);
        if (children == null || !children.isObject()) {
            return;
        }
        children.fields().forEachRemaining(entry -> validateTerminalLocalReferences(
                rootSchema,
                current.append(keyword, children).append(entry.getKey(), entry.getValue()),
                new LinkedHashSet<>(directReferenceChain),
                context
        ));
    }

    /**
     * validateSchemaChild：校验输入、状态或前置条件。
     */
    private void validateSchemaChild(
            JsonNode rootSchema,
            SchemaNode current,
            String keyword,
            Set<List<Object>> directReferenceChain,
            ReferenceTraversalContext context
    ) {
        JsonNode child = current.node().get(keyword);
        if (child == null || !child.isContainerNode() && !child.isBoolean()) {
            return;
        }
        validateTerminalLocalReferences(
                rootSchema,
                current.append(keyword, child),
                new LinkedHashSet<>(directReferenceChain),
                context
        );
    }

    /**
     * validateSchemaArrayChildren：校验输入、状态或前置条件。
     */
    private void validateSchemaArrayChildren(
            JsonNode rootSchema,
            SchemaNode current,
            String keyword,
            Set<List<Object>> directReferenceChain,
            ReferenceTraversalContext context
    ) {
        JsonNode children = current.node().get(keyword);
        if (children == null || !children.isArray()) {
            return;
        }
        for (int index = 0; index < children.size(); index++) {
            validateTerminalLocalReferences(
                    rootSchema,
                    current.append(keyword, children).append(index, children.get(index)),
                    new LinkedHashSet<>(directReferenceChain),
                    context
            );
        }
    }

    /**
     * resolveRequiredLocalReference：解析并定位可用的目标对象。
     */
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
            if (current.isArray()) {
                HttpToolArrayIndex.ParseResult result = HttpToolArrayIndex.parse(token);
                if (!result.isValid()) {
                    throw new IllegalArgumentException("JSON Schema 本地引用无效");
                }
                int index = result.requireValue();
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

    /**
     * typesFromType：处理该类内部的业务逻辑或辅助计算。
     */
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

    /**
     * addDeclaredType：处理该类内部的业务逻辑或辅助计算。
     */
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

    /**
     * typeOfValue：处理该类内部的业务逻辑或辅助计算。
     */
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

    /**
     * addArrayItemTypes：处理该类内部的业务逻辑或辅助计算。
     */
    private static void addArrayItemTypes(Set<ValueType> itemTypes, JsonNode value) {
        if (value.isArray()) {
            value.forEach(item -> itemTypes.add(typeOfValue(item)));
        }
    }

    /**
     * withoutNull：处理该类内部的业务逻辑或辅助计算。
     */
    private static Set<ValueType> withoutNull(Set<ValueType> types) {
        Set<ValueType> result = EnumSet.noneOf(ValueType.class);
        result.addAll(types);
        result.remove(ValueType.NULL);
        return Set.copyOf(result);
    }

    /**
     * intersectTypes：处理该类内部的业务逻辑或辅助计算。
     */
    private static Set<ValueType> intersectTypes(Set<ValueType> left, Set<ValueType> right) {
        Set<ValueType> result = EnumSet.noneOf(ValueType.class);
        result.addAll(left);
        result.retainAll(right);
        return Set.copyOf(result);
    }

    /**
     * sourceMissing：处理该类内部的业务逻辑或辅助计算。
     */
    private static IllegalArgumentException sourceMissing() {
        return new IllegalArgumentException("sourcePointer 未指向 Schema 中存在的输入节点");
    }

    /**
     * ValueType：枚举本模块使用的有限状态或类型。
     */
    private enum ValueType {
        /** JSON 字符串值。 */
        STRING,
        /** JSON 整数值。 */
        INTEGER,
        /** JSON 非整数数值。 */
        NUMBER,
        /** JSON 布尔值。 */
        BOOLEAN,
        /** JSON 对象值。 */
        OBJECT,
        /** JSON 数组值。 */
        ARRAY,
        /** JSON 空值。 */
        NULL
    }

    /**
     * SchemaShape：不可变数据载体，用于在本模块内传递结构化信息。
     */
    record SchemaShape(Set<ValueType> types, Set<ValueType> arrayItemTypes) {
        SchemaShape {
            types = Set.copyOf(types);
            arrayItemTypes = Set.copyOf(arrayItemTypes);
        }
        /**
         * hasNoKnownNonNullType：判断当前条件是否成立。
         */
        boolean hasNoKnownNonNullType() {
            return withoutNull(types).isEmpty();
        }
        /**
         * isOnlyArray：判断当前条件是否成立。
         */
        boolean isOnlyArray() {
            return withoutNull(types).equals(Set.of(ValueType.ARRAY));
        }
        /**
         * hasArrayAlternative：判断当前条件是否成立。
         */
        boolean hasArrayAlternative() {
            return withoutNull(types).contains(ValueType.ARRAY);
        }
        /**
         * isScalarSafe：判断当前条件是否成立。
         */
        boolean isScalarSafe() {
            return SCALAR_TYPES.containsAll(withoutNull(types));
        }
        /**
         * isQuerySafe：判断当前条件是否成立。
         */
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

        /**
         * all：处理该类内部的业务逻辑或辅助计算。
         */
        private static SchemaShape all() {
            return new SchemaShape(ALL_TYPES, ALL_TYPES);
        }

        /**
         * empty：处理该类内部的业务逻辑或辅助计算。
         */
        private static SchemaShape empty() {
            return new SchemaShape(Set.of(), Set.of());
        }

        /**
         * intersect：处理该类内部的业务逻辑或辅助计算。
         */
        private SchemaShape intersect(SchemaShape other) {
            Set<ValueType> intersectedTypes = EnumSet.noneOf(ValueType.class);
            intersectedTypes.addAll(types);
            intersectedTypes.retainAll(other.types);
            Set<ValueType> intersectedItems = EnumSet.noneOf(ValueType.class);
            intersectedItems.addAll(arrayItemTypes);
            intersectedItems.retainAll(other.arrayItemTypes);
            return new SchemaShape(intersectedTypes, intersectedItems);
        }

        /**
         * union：处理该类内部的业务逻辑或辅助计算。
         */
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

    /**
     * SchemaNode：不可变数据载体，用于在本模块内传递结构化信息。
     */
    private record SchemaNode(JsonNode node, List<Object> schemaPath) {
        private SchemaNode {
            schemaPath = List.copyOf(schemaPath);
        }

        /**
         * append：追加处理结果或审计记录。
         */
        private SchemaNode append(Object pathElement, JsonNode child) {
            List<Object> childPath = new ArrayList<>(schemaPath);
            childPath.add(pathElement);
            return new SchemaNode(child, childPath);
        }
    }

    /**
     * SourceTraversalKey：不可变数据载体，用于在本模块内传递结构化信息。
     */
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

    /**
     * ContainerTraversalKey：不可变数据载体，用于在本模块内传递结构化信息。
     */
    private record ContainerTraversalKey(List<Object> schemaPath, Set<ValueType> allowedContainerTypes) {
        private ContainerTraversalKey {
            schemaPath = List.copyOf(schemaPath);
            allowedContainerTypes = Set.copyOf(allowedContainerTypes);
        }
    }

    /**
     * Projection：不可变数据载体，用于在本模块内传递结构化信息。
     */
    private record Projection(boolean found, JsonNode schema) {
        /**
         * missing：处理该类内部的业务逻辑或辅助计算。
         */
        private static Projection missing(ObjectMapper objectMapper) {
            return new Projection(false, objectMapper.getNodeFactory().booleanNode(true));
        }
    }

    /**
     * PathShape：不可变数据载体，用于在本模块内传递结构化信息。
     */
    private record PathShape(boolean found, SchemaShape shape) {
        /**
         * missing：处理该类内部的业务逻辑或辅助计算。
         */
        private static PathShape missing() {
            return new PathShape(false, SchemaShape.all());
        }
    }

    /**
     * ReferenceTraversalState：不可变数据载体，用于在本模块内传递结构化信息。
     */
    private record ReferenceTraversalState(
            List<Object> schemaPath,
            Set<List<Object>> directReferenceChain
    ) {
        private ReferenceTraversalState {
            schemaPath = List.copyOf(schemaPath);
            directReferenceChain = Set.copyOf(directReferenceChain);
        }
    }

    /**
     * ReferenceTraversalContext：封装本模块的相关实现逻辑。
     */
    private static final class ReferenceTraversalContext {
        private final Set<List<Object>> visitingPaths = new HashSet<>();
        private final Set<ReferenceTraversalState> completedStates = new HashSet<>();
        private int traversalDepth;
        private int traversedStates;

        /**
         * isCompleted：判断当前条件是否成立。
         */
        private boolean isCompleted(ReferenceTraversalState state) {
            return completedStates.contains(state);
        }

        /**
         * enter：处理该类内部的业务逻辑或辅助计算。
         */
        private boolean enter(List<Object> schemaPath) {
            return visitingPaths.add(schemaPath);
        }

        /**
         * exit：处理该类内部的业务逻辑或辅助计算。
         */
        private void exit(List<Object> schemaPath) {
            visitingPaths.remove(schemaPath);
        }

        /**
         * complete：处理该类内部的业务逻辑或辅助计算。
         */
        private void complete(ReferenceTraversalState state) {
            completedStates.add(state);
        }

        /**
         * enterTraversalDepth：处理该类内部的业务逻辑或辅助计算。
         */
        private void enterTraversalDepth() {
            traversalDepth++;
            if (traversalDepth > MAX_REFERENCE_TRAVERSAL_DEPTH) {
                throw new IllegalArgumentException("JSON Schema 递归深度超过安全上限");
            }
        }

        /**
         * exitTraversalDepth：处理该类内部的业务逻辑或辅助计算。
         */
        private void exitTraversalDepth() {
            traversalDepth--;
        }

        /**
         * recordTraversalState：处理该类内部的业务逻辑或辅助计算。
         */
        private void recordTraversalState() {
            traversedStates++;
            if (traversedStates > MAX_REFERENCE_TRAVERSAL_STATES) {
                throw new IllegalArgumentException("JSON Schema 遍历复杂度超过安全上限");
            }
        }
    }
}
