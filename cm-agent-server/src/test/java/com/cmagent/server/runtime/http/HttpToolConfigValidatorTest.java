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

class HttpToolConfigValidatorTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private final HttpToolConfigValidator validator = new HttpToolConfigValidator(new ObjectMapper());

    @Test
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
    void rejectsInvalidSchemaKeywordShape() {
        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST, "https://api.example.test/items",
                "{\"type\":\"object\",\"properties\":[]}", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Schema");
    }

    @Test
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
    void rejectsMalformedJsonPointerEscape() {
        HttpParameterMapping mapping = mapping("/bad~2pointer", HttpParameterLocation.QUERY,
                "value", "", false, "");

        assertThatThrownBy(() -> validator.validate(config(HttpToolMethod.POST,
                "https://api.example.test/items", objectSchema("bad~2pointer", "string"), List.of(mapping))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON Pointer");
    }

    @Test
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

    private static HttpToolConfig config(
            HttpToolMethod method,
            String urlTemplate,
            String schema,
            List<HttpParameterMapping> mappings
    ) {
        return new HttpToolConfig(TENANT_ID, TOOL_ID, method, urlTemplate, schema, mappings, Map.of(),
                Duration.ofSeconds(5));
    }

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

    private static String objectSchema(String property, String type) {
        return "{\"type\":\"object\",\"properties\":{\"" + property + "\":{\"type\":\"" + type + "\"}}}";
    }

    private static String twoPropertySchema() {
        return """
                {"type":"object","properties":{"first":{"type":"string"},"second":{"type":"string"}}}
                """;
    }
}
