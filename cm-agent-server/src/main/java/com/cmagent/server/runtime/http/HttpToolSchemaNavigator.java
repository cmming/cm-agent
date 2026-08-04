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
 * 负责 HTTP 工具输入 Schema 的路径导航、局部投影、引用解析、类型域推导，
 * 并校验嵌套对象、数组和 {@code required} 约束。
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
     * 创建 {@code HttpToolSchemaNavigator} 实例并保存其运行所需依赖。
     *
     * @param objectMapper JSON 映射器，用于序列化或解析 JSON。
     */
    HttpToolSchemaNavigator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }
    /**
     * 投影源路径对应的 Schema，并保留验证默认值所需定义。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param sourcePointer 参数映射使用的源 JSON Pointer。
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
     * 分析源路径可能出现的值类型以及缺失可能性。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param sourcePointer 参数映射使用的源 JSON Pointer。
     */
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
    /**
     * 校验终点 Schema 中的本地引用可解析且无非法循环。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param sourcePointers 全部参数映射使用的源 JSON Pointer 集合。
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
     * 投影源路径对应的 Schema，并保留验证默认值所需定义。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param current 当前遍历或异常链节点。
     * @param tokens 拆分并反转义后的路径片段列表。
     * @param tokenIndex 当前处理的路径片段下标。
     * @param allowedContainerTypes 控制 allowedContainerTypes 对应处理分支的布尔开关。
     * @param visited 已访问的 Schema 节点集合，用于检测循环引用。
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
        // 路径、片段位置和容器类型共同组成遍历状态，用于阻断引用或组合分支形成的递归环。
        if (!visited.add(key) || !current.node().isObject()) {
            return Projection.missing(objectMapper);
        }

        Set<ValueType> parentTypes = intersectTypes(
                withoutNull(analyzeShape(rootSchema, current, new HashSet<>()).types()), allowedContainerTypes
        );
        String token = tokens.get(tokenIndex);
        List<JsonNode> cumulative = new ArrayList<>();
        List<JsonNode> directAlternatives = new ArrayList<>();
        // 同一路径片段可能命中对象属性或数组元素，两者作为直接备选分支合并。
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

        // $ref 与 allOf 都会累积约束，必须保留而不能用后解析的分支覆盖直接路径结果。
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
                // 仅保留实际包含目标路径的组合分支，避免无关分支把投影误判为缺失。
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
        // 多来源约束最终以 allOf 累积，保持原 Schema 对目标值的共同限制。
        return new Projection(true, cumulativeSchema(cumulative));
    }

    /**
     * 合并 anyOf 或 oneOf 分支得到备选 Schema。
     *
     * @param keyword 当前处理的 JSON Schema 关键字。
     * @param alternatives 组合关键字声明的备选 Schema 列表。
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
     * 合并 allOf 分支得到累计约束 Schema。
     *
     * @param constraints 当前汇总的 Schema 约束集合。
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
     * 分析某条源路径沿途需要的对象或数组容器形态。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param current 当前遍历或异常链节点。
     * @param tokens 拆分并反转义后的路径片段列表。
     * @param tokenIndex 当前处理的路径片段下标。
     * @param allowedContainerTypes 控制 allowedContainerTypes 对应处理分支的布尔开关。
     * @param visited 已访问的 Schema 节点集合，用于检测循环引用。
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
     * 解析源路径在各组合 Schema 分支中可能对应的节点。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param sourcePointer 参数映射使用的源 JSON Pointer。
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
     * 查找当前 Schema 节点指定属性的所有候选子 Schema。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param current 当前遍历或异常链节点。
     * @param token 当前 JSON Pointer 路径片段。
     * @param allowedContainerTypes 控制 allowedContainerTypes 对应处理分支的布尔开关。
     * @param visited 已访问的 Schema 节点集合，用于检测循环引用。
     * @param matches 用于收集候选子 Schema 的结果集合。
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
     * 解析数组 items 对当前索引生效的子 Schema。
     *
     * @param current 当前遍历或异常链节点。
     * @param itemIndex 目标数组元素下标。
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
     * 按父节点约束解析数组索引片段。
     *
     * @param parentTypes 父 Schema 允许的值类型集合。
     * @param token 当前 JSON Pointer 路径片段。
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
     * 汇总 Schema 节点声明及组合分支中的可能类型。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param schemaNode 当前分析的 Schema 节点。
     * @param visited 已访问的 Schema 节点集合，用于检测循环引用。
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
     * 解析本地 $ref，并用遍历状态防止循环引用。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param schemaNode 当前分析的 Schema 节点。
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
     * 校验终点 Schema 中的本地引用可解析且无非法循环。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param current 当前遍历或异常链节点。
     * @param directReferenceChain 当前直接 $ref 引用链。
     * @param context 本次 Schema 分析的遍历上下文。
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
     * 逐项校验对象形式 Schema 关键字的子节点。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param current 当前遍历或异常链节点。
     * @param keyword 当前处理的 JSON Schema 关键字。
     * @param directReferenceChain 当前直接 $ref 引用链。
     * @param context 本次 Schema 分析的遍历上下文。
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
     * 校验单个 Schema 子节点及其本地引用。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param current 当前遍历或异常链节点。
     * @param keyword 当前处理的 JSON Schema 关键字。
     * @param directReferenceChain 当前直接 $ref 引用链。
     * @param context 本次 Schema 分析的遍历上下文。
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
     * 逐项校验数组形式 Schema 关键字的子节点。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param current 当前遍历或异常链节点。
     * @param keyword 当前处理的 JSON Schema 关键字。
     * @param directReferenceChain 当前直接 $ref 引用链。
     * @param context 本次 Schema 分析的遍历上下文。
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
     * 解析必须存在的本地引用，否则抛出配置错误。
     *
     * @param rootSchema 工具输入的根 JSON Schema。
     * @param reference 待解析的本地 $ref 文本。
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
     * 从 type 关键字提取声明的值类型集合。
     *
     * @param typeNode Schema 的 type 关键字节点。
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
     * 把单个 Schema 类型名称加入值类型集合。
     *
     * @param types 当前汇总的 Schema 值类型集合。
     * @param declaredType Schema 显式声明的类型名称。
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
     * 根据 JSON 默认值节点判断其实际类型。
     *
     * @param value 待检查、转换或规范化的值。
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
     * 汇总数组 items 声明的元素类型。
     *
     * @param itemTypes 数组元素可能的值类型集合。
     * @param value 待检查、转换或规范化的值。
     */
    private static void addArrayItemTypes(Set<ValueType> itemTypes, JsonNode value) {
        if (value.isArray()) {
            value.forEach(item -> itemTypes.add(typeOfValue(item)));
        }
    }

    /**
     * 从值类型集合中移除 null。
     *
     * @param types 当前汇总的 Schema 值类型集合。
     */
    private static Set<ValueType> withoutNull(Set<ValueType> types) {
        Set<ValueType> result = EnumSet.noneOf(ValueType.class);
        result.addAll(types);
        result.remove(ValueType.NULL);
        return Set.copyOf(result);
    }

    /**
     * 计算两个值类型集合的交集。
     *
     * @param left 参与集合运算的左侧值。
     * @param right 参与集合运算的右侧值。
     */
    private static Set<ValueType> intersectTypes(Set<ValueType> left, Set<ValueType> right) {
        Set<ValueType> result = EnumSet.noneOf(ValueType.class);
        result.addAll(left);
        result.retainAll(right);
        return Set.copyOf(result);
    }

    /**
     * 创建“源路径不存在”的受控配置异常。
     */
    private static IllegalArgumentException sourceMissing() {
        return new IllegalArgumentException("sourcePointer 未指向 Schema 中存在的输入节点");
    }

    /**
     * 枚举 {@code ValueType} 支持的有限状态或类型。
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
     * 封装 {@code SchemaShape} 在 HTTP 工具流程中使用的不可变数据。
     */
    record SchemaShape(Set<ValueType> types, Set<ValueType> arrayItemTypes) {
        /**
         * 校验并构造 {@code SchemaShape} 实例。
         *
     * @param types 当前 Schema 节点允许的 JSON 类型集合
     * @param arrayItemTypes 数组元素允许的 JSON 类型集合
         */
        SchemaShape {
            types = Set.copyOf(types);
            arrayItemTypes = Set.copyOf(arrayItemTypes);
        }
        /**
         * 判断 Schema 是否没有任何已知非 null 类型。
         */
        boolean hasNoKnownNonNullType() {
            return withoutNull(types).isEmpty();
        }
        /**
         * 判断 Schema 是否只允许数组。
         */
        boolean isOnlyArray() {
            return withoutNull(types).equals(Set.of(ValueType.ARRAY));
        }
        /**
         * 判断 Schema 是否包含数组分支。
         */
        boolean hasArrayAlternative() {
            return withoutNull(types).contains(ValueType.ARRAY);
        }
        /**
         * 判断 Schema 的非 null 类型是否都可安全转为标量文本。
         */
        boolean isScalarSafe() {
            return SCALAR_TYPES.containsAll(withoutNull(types));
        }
        /**
         * 判断 Schema 是否适合转换为查询参数。
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
         * 创建包含全部值类型的集合。
         */
        private static SchemaShape all() {
            return new SchemaShape(ALL_TYPES, ALL_TYPES);
        }

        /**
         * 创建不包含任何值类型的集合。
         */
        private static SchemaShape empty() {
            return new SchemaShape(Set.of(), Set.of());
        }

        /**
         * 返回两个 Schema 节点约束的交集。
         *
         * @param other 参与集合运算的另一个 Schema 类型集合。
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
         * 返回两个 Schema 节点约束的并集。
         *
         * @param other 参与集合运算的另一个 Schema 类型集合。
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
     * 创建 {@code SchemaNode} 实例并保存其运行所需依赖。
     */
    private record SchemaNode(JsonNode node, List<Object> schemaPath) {
        /**
         * 校验并构造 {@code SchemaNode} 实例。
         *
     * @param node 当前解析或清理的 JSON 节点
     * @param schemaPath 当前节点在输入 Schema 中的定位路径
         */
        private SchemaNode {
            schemaPath = List.copyOf(schemaPath);
        }

        /**
         * 在当前 Schema 路径后追加一个片段。
         *
         * @param pathElement 当前待解析的路径片段。
         * @param child 当前父节点下待校验的子 Schema。
         */
        private SchemaNode append(Object pathElement, JsonNode child) {
            List<Object> childPath = new ArrayList<>(schemaPath);
            childPath.add(pathElement);
            return new SchemaNode(child, childPath);
        }
    }

    /**
     * 封装 {@code SourceTraversalKey} 在 HTTP 工具流程中使用的不可变数据。
     */
    private record SourceTraversalKey(
            List<Object> schemaPath,
            int tokenIndex,
            Set<ValueType> allowedContainerTypes
    ) {
        /**
         * 校验并构造 {@code SourceTraversalKey} 实例。
         *
     * @param schemaPath 当前节点在输入 Schema 中的定位路径
     * @param tokenIndex 当前正在解析的 JSON Pointer 片段下标
     * @param allowedContainerTypes 当前层级允许作为容器的 JSON 类型集合
         */
        private SourceTraversalKey {
            schemaPath = List.copyOf(schemaPath);
            allowedContainerTypes = Set.copyOf(allowedContainerTypes);
        }
    }

    /**
     * 封装 {@code ContainerTraversalKey} 在 HTTP 工具流程中使用的不可变数据。
     */
    private record ContainerTraversalKey(List<Object> schemaPath, Set<ValueType> allowedContainerTypes) {
        /**
         * 校验并构造 {@code ContainerTraversalKey} 实例。
         *
     * @param schemaPath 当前节点在输入 Schema 中的定位路径
     * @param allowedContainerTypes 当前层级允许作为容器的 JSON 类型集合
         */
        private ContainerTraversalKey {
            schemaPath = List.copyOf(schemaPath);
            allowedContainerTypes = Set.copyOf(allowedContainerTypes);
        }
    }

    /**
     * 封装 {@code Projection} 在 HTTP 工具流程中使用的不可变数据。
     */
    private record Projection(boolean found, JsonNode schema) {
        /**
         * 创建表示源路径缺失的分析结果。
         *
         * @param objectMapper JSON 映射器，用于序列化或解析 JSON。
         */
        private static Projection missing(ObjectMapper objectMapper) {
            return new Projection(false, objectMapper.getNodeFactory().booleanNode(true));
        }
    }

    /**
     * 封装 {@code PathShape} 在 HTTP 工具流程中使用的不可变数据。
     */
    private record PathShape(boolean found, SchemaShape shape) {
        /**
         * 创建表示源路径缺失的分析结果。
         */
        private static PathShape missing() {
            return new PathShape(false, SchemaShape.all());
        }
    }

    /**
     * 封装 {@code ReferenceTraversalState} 在 HTTP 工具流程中使用的不可变数据。
     */
    private record ReferenceTraversalState(
            List<Object> schemaPath,
            Set<List<Object>> directReferenceChain
    ) {
        /**
         * 校验并构造 {@code ReferenceTraversalState} 实例。
         *
     * @param schemaPath 当前节点在输入 Schema 中的定位路径
     * @param directReferenceChain 用于检测循环引用的直接 Schema 引用链
         */
        private ReferenceTraversalState {
            schemaPath = List.copyOf(schemaPath);
            directReferenceChain = Set.copyOf(directReferenceChain);
        }
    }

    /**
     * 创建 {@code ReferenceTraversalContext} 实例并保存其运行所需依赖。
     */
    private static final class ReferenceTraversalContext {
        private final Set<List<Object>> visitingPaths = new HashSet<>();
        private final Set<ReferenceTraversalState> completedStates = new HashSet<>();
        private int traversalDepth;
        private int traversedStates;

        /**
         * 判断本地引用是否已经完成校验。
         *
         * @param state 当前遍历状态。
         */
        private boolean isCompleted(ReferenceTraversalState state) {
            return completedStates.contains(state);
        }

        /**
         * 进入本地引用遍历并检测循环。
         *
         * @param schemaPath 当前节点在 Schema 文档中的路径。
         */
        private boolean enter(List<Object> schemaPath) {
            return visitingPaths.add(schemaPath);
        }

        /**
         * 退出当前本地引用遍历节点。
         *
         * @param schemaPath 当前节点在 Schema 文档中的路径。
         */
        private void exit(List<Object> schemaPath) {
            visitingPaths.remove(schemaPath);
        }

        /**
         * 标记当前本地引用已完成校验。
         *
         * @param state 当前遍历状态。
         */
        private void complete(ReferenceTraversalState state) {
            completedStates.add(state);
        }

        /**
         * 增加遍历深度并执行复杂度上限检查。
         */
        private void enterTraversalDepth() {
            traversalDepth++;
            if (traversalDepth > MAX_REFERENCE_TRAVERSAL_DEPTH) {
                throw new IllegalArgumentException("JSON Schema 递归深度超过安全上限");
            }
        }

        /**
         * 减少当前遍历深度。
         */
        private void exitTraversalDepth() {
            traversalDepth--;
        }

        /**
         * 记录已访问节点并执行总遍历次数上限检查。
         */
        private void recordTraversalState() {
            traversedStates++;
            if (traversedStates > MAX_REFERENCE_TRAVERSAL_STATES) {
                throw new IllegalArgumentException("JSON Schema 遍历复杂度超过安全上限");
            }
        }
    }
}
