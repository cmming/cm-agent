package com.cmagent.server.runtime.http;

import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class HttpToolConfigValidatorTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private final HttpToolConfigValidator validator = new HttpToolConfigValidator(new ObjectMapper());

    @Test
    /**
     * 验证 {@code InvalidJsonAndSchemaWhoseInputRootIsNotObject} 异常场景会被正确拒绝。
     */
    void rejectsInvalidJsonAndSchemaWhoseInputRootIsNotObject() {
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST, "https://api.example.test/items",
                "{", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputSchema");

        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST, "https://api.example.test/items",
                "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("object");

        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST, "https://api.example.test/items",
                "{\"type\":[\"object\",\"array\"]}", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("object");
    }

    @Test
    /**
     * 验证 {@code InvalidSchemaKeywordShape} 异常场景会被正确拒绝。
     */
    void rejectsInvalidSchemaKeywordShape() {
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST, "https://api.example.test/items",
                "{\"type\":\"object\",\"properties\":[]}", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Schema");
    }

    @Test
    /**
     * 验证 {@code SchemaThatDeclaresAnOlderDraft} 异常场景会被正确拒绝。
     */
    void rejectsSchemaThatDeclaresAnOlderDraft() {
        String draftSeven = """
                {"$schema":"http://json-schema.org/draft-07/schema#","type":"object"}
                """;

        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", draftSeven, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2020-12");
    }

    @Test
    /**
     * 验证或支持 {@code resolvesEscapedJsonPointerAndRejectsMissingInputNode} 所描述的测试场景。
     */
    void resolvesEscapedJsonPointerAndRejectsMissingInputNode() {
        String schema = """
                {"type":"object","properties":{"order/id":{"type":"object","properties":{"~code":{"type":"string"}}}}}
                """;
        HttpParameterMapping escaped = mapping("/order~1id/~0code", HttpParameterLocation.QUERY,
                "code", "", false, "");

        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", schema, List.of(escaped)))).doesNotThrowAnyException();

        HttpParameterMapping missing = mapping("/order~1id/missing", HttpParameterLocation.QUERY,
                "code", "", false, "");
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", schema, List.of(missing))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourcePointer");
    }

    @Test
    /**
     * 验证 {@code MalformedJsonPointerEscape} 异常场景会被正确拒绝。
     */
    void rejectsMalformedJsonPointerEscape() {
        HttpParameterMapping mapping = mapping("/bad~2pointer", HttpParameterLocation.QUERY,
                "value", "", false, "");

        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", objectSchema("bad~2pointer", "string"), List.of(mapping))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON Pointer");
    }

    @Test
    /**
     * 验证系统会校验 {@code DefaultJsonWithCorrespondingSubSchema}。
     */
    void validatesDefaultJsonWithCorrespondingSubSchema() {
        HttpParameterMapping invalidJson = mapping("/limit", HttpParameterLocation.QUERY,
                "limit", "", false, "not-json");
        HttpParameterMapping invalidType = mapping("/limit", HttpParameterLocation.QUERY,
                "limit", "", false, "\"ten\"");

        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", objectSchema("limit", "integer"), List.of(invalidJson))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultValueJson");
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", objectSchema("limit", "integer"), List.of(invalidType))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultValueJson");
    }

    @Test
    /**
     * 验证系统会校验 {@code DefaultWithRefSiblingAndRootDefinitionsContext}。
     */
    void validatesDefaultWithRefSiblingAndRootDefinitionsContext() {
        String refSiblingSchema = """
                {"type":"object","$defs":{"text":{"type":"string"}},"properties":{
                  "name":{"$ref":"#/$defs/text","maxLength":3}
                }}
                """;
        HttpParameterMapping tooLong = mapping("/name", HttpParameterLocation.QUERY,
                "name", "", false, "\"long\"");
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", refSiblingSchema, List.of(tooLong))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultValueJson");

        String allOfSchema = """
                {"type":"object","$defs":{"positive":{"type":"integer","minimum":1}},"properties":{
                  "count":{"allOf":[{"$ref":"#/$defs/positive"}]}
                }}
                """;
        HttpParameterMapping valid = mapping("/count", HttpParameterLocation.QUERY,
                "count", "", false, "2");
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", allOfSchema, List.of(valid))))
                .doesNotThrowAnyException();
    }

    @Test
    /**
     * 验证或支持 {@code resolvesPropertiesInAllOfAndInfersConservativeCompositionTypes} 所描述的测试场景。
     */
    void resolvesPropertiesInAllOfAndInfersConservativeCompositionTypes() {
        String allOfProperties = """
                {"type":"object","allOf":[
                  {"properties":{"code":{"type":"string"}}},
                  {"properties":{"code":{"maxLength":10}}}
                ]}
                """;
        HttpParameterMapping code = mapping("/code", HttpParameterLocation.HEADER,
                "X-Code", "", false, "");
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", allOfProperties, List.of(code))))
                .doesNotThrowAnyException();

        String scalarCompositions = """
                {"type":"object","properties":{
                  "choice":{"oneOf":[{"type":"string"},{"type":"integer"}]},
                  "status":{"enum":["ready",2]},
                  "enabled":{"const":true},
                  "query":{"anyOf":[{"type":"string"},{"type":"array","items":{"type":"integer"}}]}
                }}
                """;
        List<HttpParameterMapping> scalarMappings = List.of(
                mapping("/choice", HttpParameterLocation.HEADER, "X-Choice", "", false, ""),
                mapping("/status", HttpParameterLocation.QUERY, "status", "", false, ""),
                mapping("/enabled", HttpParameterLocation.HEADER, "X-Enabled", "", false, ""),
                mapping("/query", HttpParameterLocation.QUERY, "query", "", false, "")
        );
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", scalarCompositions, scalarMappings)))
                .doesNotThrowAnyException();

        String unsafeComposition = """
                {"type":"object","properties":{"value":{"anyOf":[
                  {"type":"string"},{"type":"object"}
                ]}}}
                """;
        HttpParameterMapping unsafe = mapping("/value", HttpParameterLocation.QUERY,
                "value", "", false, "");
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", unsafeComposition, List.of(unsafe))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BODY");
    }

    @Test
    /**
     * 验证 {@code BodyIntermediateContainerShapeConflictsInEitherOrder} 异常场景会被正确拒绝。
     */
    void rejectsBodyIntermediateContainerShapeConflictsInEitherOrder() {
        HttpParameterMapping array = mapping("/first", HttpParameterLocation.BODY,
                "", "/payload/0", false, "");
        HttpParameterMapping object = mapping("/second", HttpParameterLocation.BODY,
                "", "/payload/name", false, "");

        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", twoPropertySchema(), List.of(array, object))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BODY");
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", twoPropertySchema(), List.of(object, array))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BODY");

        List<HttpParameterMapping> compatible = List.of(
                mapping("/first", HttpParameterLocation.BODY, "", "/payload/0/name", false, ""),
                mapping("/second", HttpParameterLocation.BODY, "", "/payload/1/name", false, ""),
                mapping("/first", HttpParameterLocation.BODY, "", "/metadata/name", false, ""),
                mapping("/second", HttpParameterLocation.BODY, "", "/metadata/code", false, ""),
                mapping("/first", HttpParameterLocation.BODY, "", "/a", false, ""),
                mapping("/second", HttpParameterLocation.BODY, "", "/ab", false, "")
        );
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", twoPropertySchema(), compatible)))
                .doesNotThrowAnyException();
    }

    @Test
    /**
     * 验证 {@code TrailingJsonTokensWithoutEchoingValues} 异常场景会被正确拒绝。
     */
    void rejectsTrailingJsonTokensWithoutEchoingValues() {
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", "{\"type\":\"object\"} {}", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("inputSchema 不是合法 JSON")
                .hasMessageNotContaining("type");

        HttpParameterMapping trailingDefault = mapping("/limit", HttpParameterLocation.QUERY,
                "limit", "", false, "1 2");
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", objectSchema("limit", "integer"), List.of(trailingDefault))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("defaultValueJson 必须是合法 JSON 值")
                .hasMessageNotContaining("1 2");
    }

    @Test
    /**
     * 验证 {@code NullDefaultEvenWhenSchemaIsNullable} 异常场景会被正确拒绝。
     */
    void rejectsNullDefaultEvenWhenSchemaIsNullable() {
        String schema = """
                {"type":"object","properties":{"value":{"type":["string","null"]}}}
                """;
        HttpParameterMapping mapping = mapping("/value", HttpParameterLocation.QUERY,
                "value", "", false, "null");

        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", schema, List.of(mapping))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("defaultValueJson 不能为 null");
    }

    @Test
    /**
     * 验证系统会校验 {@code DefaultsAgainstAnyProjectedCombinationBranch}。
     */
    void validatesDefaultsAgainstAnyProjectedCombinationBranch() {
        String oneOf = """
                {"type":"object","oneOf":[
                  {"properties":{"id":{"type":"string"}}},
                  {"properties":{"id":{"type":"integer"}}}
                ]}
                """;
        HttpParameterMapping stringDefault = mapping("/id", HttpParameterLocation.QUERY,
                "id", "", false, "\"x\"");
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", oneOf, List.of(stringDefault))))
                .doesNotThrowAnyException();

        HttpParameterMapping booleanDefault = mapping("/id", HttpParameterLocation.QUERY,
                "id", "", false, "true");
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", oneOf, List.of(booleanDefault))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultValueJson");

        String overlapping = """
                {"type":"object","oneOf":[
                  {"properties":{"id":{"type":"string"}}},
                  {"properties":{"id":{"type":"string","minLength":1}}}
                ]}
                """;
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", overlapping, List.of(stringDefault))))
                .doesNotThrowAnyException();

        String union = overlapping.replace("\"oneOf\"", "\"anyOf\"");
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", union, List.of(stringDefault))))
                .doesNotThrowAnyException();

        String discriminated = """
                {"type":"object","oneOf":[
                  {"properties":{"kind":{"const":"a"},"id":{"type":"string"}},"required":["kind","id"]},
                  {"properties":{"kind":{"const":"b"},"id":{"type":"string"}},"required":["kind","id"]}
                ]}
                """;
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", discriminated, List.of(stringDefault))))
                .doesNotThrowAnyException();

        String singleCandidate = """
                {"type":"object","oneOf":[
                  {"properties":{"id":{"type":"string"}}},
                  {"properties":{"kind":{"const":"b"}}}
                ]}
                """;
        HttpParameterMapping bodyStringDefault = mapping("/id", HttpParameterLocation.BODY,
                "", "/id", false, "\"x\"");
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", singleCandidate, List.of(bodyStringDefault))))
                .doesNotThrowAnyException();

        String noCandidateAccepts = """
                {"type":"object","oneOf":[
                  {"properties":{"id":{"type":"integer"}}},
                  {"properties":{"id":{"type":"boolean"}}}
                ]}
                """;
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", noCandidateAccepts, List.of(stringDefault))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultValueJson");
    }

    @Test
    /**
     * 验证或支持 {@code derivesQueryArrayItemsFromPrefixItemsConstAndEnum} 所描述的测试场景。
     */
    void derivesQueryArrayItemsFromPrefixItemsConstAndEnum() {
        String schema = """
                {"type":"object","properties":{
                  "tuple":{"type":"array","prefixItems":[{"type":"object"}],"items":false},
                  "constant":{"const":["a","b"]},
                  "choices":{"enum":[["a"],[1,2]]}
                }}
                """;
        HttpParameterMapping tuple = mapping("/tuple", HttpParameterLocation.QUERY,
                "tuple", "", false, "");
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", schema, List.of(tuple))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标量数组");

        List<HttpParameterMapping> safe = List.of(
                mapping("/constant", HttpParameterLocation.QUERY, "constant", "", false, ""),
                mapping("/choices", HttpParameterLocation.QUERY, "choices", "", false, "")
        );
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", schema, safe)))
                .doesNotThrowAnyException();
    }

    @Test
    /**
     * 验证或支持 {@code interpretsNumericSourceTokenUsingPossibleParentContainerTypes} 所描述的测试场景。
     */
    void interpretsNumericSourceTokenUsingPossibleParentContainerTypes() {
        String objectParent = """
                {"type":"object","properties":{"container":{
                  "type":"object","properties":{"0":{"type":"string"}},"items":{"type":"object"}
                }}}
                """;
        HttpParameterMapping numericProperty = mapping("/container/0", HttpParameterLocation.QUERY,
                "value", "", false, "");
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", objectParent, List.of(numericProperty))))
                .doesNotThrowAnyException();

        String arrayParent = """
                {"type":"object","properties":{"container":{
                  "type":"array","prefixItems":[{"type":"string"}],"items":false,
                  "properties":{"0":{"type":"object"}}
                }}}
                """;
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", arrayParent, List.of(numericProperty))))
                .doesNotThrowAnyException();

        String ambiguousParent = """
                {"type":"object","properties":{"container":{"anyOf":[
                  {"type":"object","properties":{"0":{"type":"string"}}},
                  {"type":"array","items":{"type":"object"}}
                ]}}}
                """;
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", ambiguousParent, List.of(numericProperty))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BODY");
    }

    @Test
    /**
     * 验证系统会校验 {@code TerminalLocalReferenceWithoutDefault}。
     */
    void validatesTerminalLocalReferenceWithoutDefault() {
        String existing = """
                {"type":"object","$defs":{"payload":{"type":"object"}},"properties":{
                  "payload":{"$ref":"#/$defs/payload"}
                }}
                """;
        HttpParameterMapping body = mapping("/payload", HttpParameterLocation.BODY,
                "", "/payload", false, "");
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", existing, List.of(body))))
                .doesNotThrowAnyException();

        String missing = """
                {"type":"object","properties":{"payload":{"$ref":"#/$defs/missing"}}}
                """;
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", missing, List.of(body))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON Schema 本地引用无效")
                .hasMessageNotContaining("missing");

        String circular = """
                {"type":"object","$defs":{"loop":{"$ref":"#/$defs/loop"}},"properties":{
                  "payload":{"$ref":"#/$defs/loop"}
                }}
                """;
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", circular, List.of(body))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON Schema 本地引用存在循环");
    }

    @Test
    /**
     * 验证或支持 {@code eagerlyValidatesLocalReferencesInsideTerminalSchemaSubtree} 所描述的测试场景。
     */
    void eagerlyValidatesLocalReferencesInsideTerminalSchemaSubtree() {
        HttpParameterMapping body = mapping("/items", HttpParameterLocation.BODY,
                "", "/items", false, "");
        String missingItemsReference = """
                {"type":"object","properties":{"items":{
                  "type":"array","items":{"$ref":"#/$defs/missing"}
                }}}
                """;
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", missingItemsReference, List.of(body))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON Schema 本地引用无效")
                .hasMessageNotContaining("missing");

        String missingNestedPropertyReference = """
                {"type":"object","properties":{"items":{"type":"object","properties":{
                  "nested":{"$ref":"#/$defs/private-value"}
                }}}}
                """;
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", missingNestedPropertyReference, List.of(body))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON Schema 本地引用无效")
                .hasMessageNotContaining("private-value");

        String recursive = """
                {"type":"object","$defs":{"node":{"type":"object","properties":{
                  "next":{"$ref":"#/$defs/node"}
                }}},"properties":{"items":{"type":"array","items":{"$ref":"#/$defs/node"}}}}
                """;
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", recursive, List.of(body))))
                .doesNotThrowAnyException();

        String instanceReferenceText = """
                {"type":"object","properties":{"items":{"type":"object",
                  "default":{"$ref":"#/$defs/missing"},
                  "examples":[{"$ref":"#/$defs/also-missing"}]
                }}}
                """;
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", instanceReferenceText, List.of(body))))
                .doesNotThrowAnyException();
    }

    @Test
    /**
     * 验证 {@code PureReferenceCyclesWrappedBySameInstanceApplicators} 异常场景会被正确拒绝。
     */
    void rejectsPureReferenceCyclesWrappedBySameInstanceApplicators() {
        HttpParameterMapping body = mapping("/payload", HttpParameterLocation.BODY,
                "", "/payload", false, "");
        Map<String, String> wrappedLoops = Map.of(
                "allOf", "{\"allOf\":[{\"$ref\":\"#/$defs/loop\"}]}",
                "anyOf", "{\"anyOf\":[{\"$ref\":\"#/$defs/loop\"}]}",
                "oneOf", "{\"oneOf\":[{\"$ref\":\"#/$defs/loop\"}]}",
                "not", "{\"not\":{\"$ref\":\"#/$defs/loop\"}}",
                "if", "{\"if\":{\"$ref\":\"#/$defs/loop\"}}",
                "then", "{\"then\":{\"$ref\":\"#/$defs/loop\"}}",
                "else", "{\"else\":{\"$ref\":\"#/$defs/loop\"}}"
        );

        wrappedLoops.forEach((keyword, loopSchema) -> {
            String schema = """
                    {"type":"object","$defs":{"loop":%s},"properties":{
                      "payload":{"$ref":"#/$defs/loop"}
                    }}
                    """.formatted(loopSchema);
            assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                    "https://api.example.test/items", schema, List.of(body))))
                    .as(keyword)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("JSON Schema 本地引用存在循环");
        });
    }

    @Test
    /**
     * 验证或支持 {@code treatsDependentSchemasAsSameInstanceApplicator} 所描述的测试场景。
     */
    void treatsDependentSchemasAsSameInstanceApplicator() {
        HttpParameterMapping body = mapping("/payload", HttpParameterLocation.BODY,
                "", "/payload", false, "");
        String dependentLoop = """
                {"type":"object","$defs":{"loop":{"dependentSchemas":{
                  "x":{"$ref":"#/$defs/loop"}
                }}},"properties":{"payload":{"$ref":"#/$defs/loop"}}}
                """;
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", dependentLoop, List.of(body))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON Schema 本地引用存在循环");

        String legalDependentSchema = """
                {"type":"object","$defs":{"rule":{"type":"object","dependentSchemas":{
                  "x":{"required":["y"]}
                }}},"properties":{"payload":{"$ref":"#/$defs/rule"}}}
                """;
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", legalDependentSchema, List.of(body))))
                .doesNotThrowAnyException();
    }

    @Test
    /**
     * 验证或支持 {@code doesNotReuseValidatedReferencePathAcrossDifferentChainContexts} 所描述的测试场景。
     */
    void doesNotReuseValidatedReferencePathAcrossDifferentChainContexts() {
        HttpParameterMapping body = mapping("/payload", HttpParameterLocation.BODY,
                "", "/payload", false, "");
        String mixedPathLoop = """
                {"type":"object","$defs":{
                  "loop":{"properties":{"child":{"$ref":"#/$defs/next"}},
                          "allOf":[{"$ref":"#/$defs/next"}]},
                  "next":{"$ref":"#/$defs/loop"}
                },"properties":{"payload":{"$ref":"#/$defs/loop"}}}
                """;
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", mixedPathLoop, List.of(body))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON Schema 本地引用存在循环");
    }

    @Test
    /**
     * 验证或支持 {@code reusesCompletedReferenceTraversalForRepeatedDag} 所描述的测试场景。
     */
    void reusesCompletedReferenceTraversalForRepeatedDag() {
        HttpParameterMapping body = mapping("/payload", HttpParameterLocation.BODY,
                "", "/payload", false, "");
        String schema = repeatedReferenceDag(20);

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> validator.validate(config(
                HttpToolMethod.POST, "https://api.example.test/items", schema, List.of(body)
        )));
    }

    @Test
    /**
     * 验证 {@code SchemaTraversalThatExceedsComplexityBudget} 异常场景会被正确拒绝。
     */
    void rejectsSchemaTraversalThatExceedsComplexityBudget() {
        HttpParameterMapping body = mapping("/payload", HttpParameterLocation.BODY,
                "", "/payload", false, "");
        String schema = wideSchema(10_001);

        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", schema, List.of(body))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON Schema 遍历复杂度超过安全上限");
    }

    @Test
    /**
     * 验证 {@code ReferenceTraversalBeyondDepthLimit} 异常场景会被正确拒绝。
     */
    void rejectsReferenceTraversalBeyondDepthLimit() {
        HttpParameterMapping body = mapping("/payload", HttpParameterLocation.BODY,
                "", "/payload", false, "");
        String schema = linearReferenceSchema(257);

        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", schema, List.of(body))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON Schema 递归深度超过安全上限");
    }

    @Test
    /**
     * 验证 {@code StructuralTraversalBeyondDepthLimit} 异常场景会被正确拒绝。
     */
    void rejectsStructuralTraversalBeyondDepthLimit() {
        HttpParameterMapping body = mapping("/payload", HttpParameterLocation.BODY,
                "", "/payload", false, "");
        String schema = deepStructuralSchema(257);

        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", schema, List.of(body))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON Schema 递归深度超过安全上限");
    }

    @Test
    /**
     * 验证 {@code ReferenceTraversalAtDepthLimit} 合法场景会被接受。
     */
    void acceptsReferenceTraversalAtDepthLimit() {
        HttpParameterMapping body = mapping("/payload", HttpParameterLocation.BODY,
                "", "/payload", false, "");
        String schema = linearReferenceSchema(256);

        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", schema, List.of(body))))
                .doesNotThrowAnyException();
    }

    @Test
    /**
     * 验证 {@code StructuralTraversalAtDepthLimit} 合法场景会被接受。
     */
    void acceptsStructuralTraversalAtDepthLimit() {
        HttpParameterMapping body = mapping("/payload", HttpParameterLocation.BODY,
                "", "/payload", false, "");
        String schema = deepStructuralSchema(256);

        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", schema, List.of(body))))
                .doesNotThrowAnyException();
    }

    @Test
    /**
     * 验证或支持 {@code enforcesArrayIndexSafetyLimitWithoutRejectingNumericObjectProperties} 所描述的测试场景。
     */
    void enforcesArrayIndexSafetyLimitWithoutRejectingNumericObjectProperties() {
        String arraySchema = """
                {"type":"object","properties":{"items":{"type":"array","items":{"type":"string"}}}}
                """;
        HttpParameterMapping boundary = mapping("/items/10000", HttpParameterLocation.QUERY,
                "value", "", false, "");
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", arraySchema, List.of(boundary))))
                .doesNotThrowAnyException();

        HttpParameterMapping overLimit = mapping("/items/10001", HttpParameterLocation.QUERY,
                "value", "", false, "");
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", arraySchema, List.of(overLimit))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON Pointer 数组索引无效或超过安全上限");

        HttpParameterMapping huge = mapping("/items/999999999999999999999999", HttpParameterLocation.QUERY,
                "value", "", false, "");
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", arraySchema, List.of(huge))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON Pointer 数组索引无效或超过安全上限")
                .hasMessageNotContaining("999999");

        String objectSchema = """
                {"type":"object","properties":{"items":{"type":"object","properties":{
                  "10001":{"type":"string"},"999999999999999999999999":{"type":"string"}
                }}}}
                """;
        List<HttpParameterMapping> numericProperties = List.of(
                mapping("/items/10001", HttpParameterLocation.QUERY, "first", "", false, ""),
                mapping("/items/999999999999999999999999", HttpParameterLocation.QUERY,
                        "second", "", false, "")
        );
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", objectSchema, numericProperties)))
                .doesNotThrowAnyException();

        String bodySchema = """
                {"type":"object","properties":{"value":{"type":"string"}}}
                """;
        HttpParameterMapping bodyBoundary = mapping("/value", HttpParameterLocation.BODY,
                "", "/items/10000", false, "");
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", bodySchema, List.of(bodyBoundary))))
                .doesNotThrowAnyException();

        HttpParameterMapping bodyOverLimit = mapping("/value", HttpParameterLocation.BODY,
                "", "/items/10001", false, "");
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", bodySchema, List.of(bodyOverLimit))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON Pointer 数组索引无效或超过安全上限");
    }

    @Test
    /**
     * 验证或支持 {@code requiresPathMappingsToExactlyMatchRequiredUrlPlaceholders} 所描述的测试场景。
     */
    void requiresPathMappingsToExactlyMatchRequiredUrlPlaceholders() {
        HttpParameterMapping path = mapping("/id", HttpParameterLocation.PATH,
                "id", "", true, "");

        assertThatCode(() -> validator.validate(config(HttpToolMethod.GET,
                "https://api.example.test/items/{id}", objectSchema("id", "string"), List.of(path))))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.GET,
                "https://api.example.test/items/{other}", objectSchema("id", "string"), List.of(path))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PATH");

        HttpParameterMapping optionalPath = mapping("/id", HttpParameterLocation.PATH,
                "id", "", false, "");
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.GET,
                "https://api.example.test/items/{id}", objectSchema("id", "string"), List.of(optionalPath))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必填");
    }

    @Test
    /**
     * 验证 {@code GetBodyDuplicateTargetsAndBodyParentChildConflicts} 异常场景会被正确拒绝。
     */
    void rejectsGetBodyDuplicateTargetsAndBodyParentChildConflicts() {
        HttpParameterMapping body = mapping("/payload", HttpParameterLocation.BODY,
                "", "/payload", true, "");
        assertThatThrownBy(() -> config(HttpToolMethod.GET, "https://api.example.test/items",
                objectSchema("payload", "object"), List.of(body)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GET");

        List<HttpParameterMapping> duplicates = List.of(
                mapping("/first", HttpParameterLocation.QUERY, "value", "", false, ""),
                mapping("/second", HttpParameterLocation.QUERY, "value", "", false, "")
        );
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", twoPropertySchema(), duplicates)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");

        List<HttpParameterMapping> bodyConflict = List.of(
                mapping("/first", HttpParameterLocation.BODY, "", "/order", false, ""),
                mapping("/second", HttpParameterLocation.BODY, "", "/order/id", false, "")
        );
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", twoPropertySchema(), bodyConflict)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BODY");
    }

    @Test
    /**
     * 验证 {@code SensitiveDynamicHeadersCaseInsensitively} 异常场景会被正确拒绝。
     */
    void rejectsSensitiveDynamicHeadersCaseInsensitively() {
        List<String> forbidden = List.of("Host", "content-length", "Connection", "Transfer-Encoding",
                "AUTHORIZATION", "Cookie", "Proxy-Authorization", "Upgrade");

        for (String header : forbidden) {
            HttpParameterMapping mapping = mapping("/value", HttpParameterLocation.HEADER,
                    header, "", false, "");
            assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                    "https://api.example.test/items", objectSchema("value", "string"), List.of(mapping))))
                    .as(header)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Header");
        }
    }

    @Test
    /**
     * 验证或支持 {@code permitsScalarArrayQueryButRestrictsComplexValuesToBody} 所描述的测试场景。
     */
    void permitsScalarArrayQueryButRestrictsComplexValuesToBody() {
        String schema = """
                {"type":"object","properties":{
                  "tags":{"type":"array","items":{"type":"string"}},
                  "filters":{"type":"object","properties":{"active":{"type":"boolean"}}},
                  "matrix":{"type":"array","items":{"type":"object"}}
                }}
                """;
        HttpParameterMapping tags = mapping("/tags", HttpParameterLocation.QUERY,
                "tag", "", false, "");
        assertThatCode(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", schema, List.of(tags)))).doesNotThrowAnyException();

        for (HttpParameterLocation location : List.of(
                HttpParameterLocation.PATH, HttpParameterLocation.QUERY, HttpParameterLocation.HEADER)) {
            HttpParameterMapping complex = mapping("/filters", location,
                    "filters", "", location == HttpParameterLocation.PATH, "");
            String url = location == HttpParameterLocation.PATH
                    ? "https://api.example.test/items/{filters}" : "https://api.example.test/items";
            assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST, url, schema, List.of(complex))))
                    .as(location.name())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("BODY");
        }

        HttpParameterMapping complexArray = mapping("/matrix", HttpParameterLocation.QUERY,
                "matrix", "", false, "");
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", schema, List.of(complexArray))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标量数组");
    }

    /**
     * 构造测试配置。
     *
     * @param method 测试辅助方法使用的 method 参数
     * @param urlTemplate 测试辅助方法使用的 urlTemplate 参数
     * @param schema 测试辅助方法使用的 schema 参数
     * @param mappings 测试辅助方法使用的 mappings 参数
     */
    private static HttpToolConfig config(
            HttpToolMethod method,
            String urlTemplate,
            String schema,
            List<HttpParameterMapping> mappings
    ) {
        return new HttpToolConfig(TENANT_ID, TOOL_ID, method, urlTemplate, schema, mappings, Map.of(),
                Duration.ofSeconds(5));
    }

    /**
     * 验证或支持 {@code repeatedReferenceDag} 所描述的测试场景。
     *
     * @param depth 测试辅助方法使用的 depth 参数
     */
    private static String repeatedReferenceDag(int depth) {
        StringBuilder definitions = new StringBuilder("\"n0\":{\"type\":\"string\"}");
        for (int index = 1; index <= depth; index++) {
            definitions.append(",\"n").append(index).append("\":{\"allOf\":[")
                    .append("{\"$ref\":\"#/$defs/n").append(index - 1).append("\"},")
                    .append("{\"$ref\":\"#/$defs/n").append(index - 1).append("\"}]}");
        }
        return "{\"type\":\"object\",\"$defs\":{" + definitions
                + "},\"properties\":{\"payload\":{\"$ref\":\"#/$defs/n" + depth + "\"}}}";
    }

    /**
     * 验证或支持 {@code wideSchema} 所描述的测试场景。
     *
     * @param width 测试辅助方法使用的 width 参数
     */
    private static String wideSchema(int width) {
        StringBuilder branches = new StringBuilder();
        for (int index = 0; index < width; index++) {
            if (index > 0) {
                branches.append(',');
            }
            branches.append("{\"type\":\"string\"}");
        }
        return "{\"type\":\"object\",\"properties\":{\"payload\":{\"allOf\":["
                + branches + "]}}}";
    }

    /**
     * 验证或支持 {@code linearReferenceSchema} 所描述的测试场景。
     *
     * @param traversalDepth 测试辅助方法使用的 traversalDepth 参数
     */
    private static String linearReferenceSchema(int traversalDepth) {
        StringBuilder definitions = new StringBuilder("\"n0\":{\"type\":\"string\"}");
        for (int index = 1; index < traversalDepth - 1; index++) {
            definitions.append(",\"n").append(index).append("\":{\"$ref\":\"#/$defs/n")
                    .append(index - 1).append("\"}");
        }
        return "{\"type\":\"object\",\"$defs\":{" + definitions
                + "},\"properties\":{\"payload\":{\"$ref\":\"#/$defs/n"
                + (traversalDepth - 2) + "\"}}}";
    }

    /**
     * 验证或支持 {@code deepStructuralSchema} 所描述的测试场景。
     *
     * @param traversalDepth 测试辅助方法使用的 traversalDepth 参数
     */
    private static String deepStructuralSchema(int traversalDepth) {
        String nested = "{\"type\":\"string\"}";
        for (int depth = 1; depth < traversalDepth; depth++) {
            if (depth % 2 == 0) {
                nested = "{\"type\":\"array\",\"items\":" + nested + "}";
            } else {
                nested = "{\"type\":\"object\",\"properties\":{\"next\":" + nested + "}}";
            }
        }
        return "{\"type\":\"object\",\"properties\":{\"payload\":" + nested + "}}";
    }

    /**
     * 验证或支持 {@code mapping} 所描述的测试场景。
     *
     * @param sourcePointer 测试辅助方法使用的 sourcePointer 参数
     * @param location 测试辅助方法使用的 location 参数
     * @param targetName 测试辅助方法使用的 targetName 参数
     * @param targetPointer 测试辅助方法使用的 targetPointer 参数
     * @param required 测试辅助方法使用的 required 参数
     * @param defaultValueJson 测试辅助方法使用的 defaultValueJson 参数
     */
    private static HttpParameterMapping mapping(
            String sourcePointer,
            HttpParameterLocation location,
            String targetName,
            String targetPointer,
            boolean required,
            String defaultValueJson
    ) {
        return new HttpParameterMapping(sourcePointer, location, targetName, targetPointer, required, defaultValueJson);
    }

    /**
     * 验证或支持 {@code objectSchema} 所描述的测试场景。
     *
     * @param property 测试辅助方法使用的 property 参数
     * @param type 测试辅助方法使用的 type 参数
     */
    private static String objectSchema(String property, String type) {
        return "{\"type\":\"object\",\"properties\":{\"" + property + "\":{\"type\":\"" + type + "\"}}}";
    }

    /**
     * 验证或支持 {@code twoPropertySchema} 所描述的测试场景。
     */
    private static String twoPropertySchema() {
        return """
                {"type":"object","properties":{"first":{"type":"string"},"second":{"type":"string"}}}
                """;
    }
}
