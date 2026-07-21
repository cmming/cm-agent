package com.cmagent.server.runtime.http;

import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpToolInputMapperTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpToolConfigValidator validator = new HttpToolConfigValidator(objectMapper);
    private final HttpToolInputMapper mapper = new HttpToolInputMapper(objectMapper, validator);

    @Test
    void appliesDefaultToMissingAndExplicitNullValues() throws Exception {
        HttpToolConfig config = config("""
                {"type":"object","properties":{"page":{"type":"integer"},"lang":{"type":"string"}}}
                """, List.of(
                mapping("/page", HttpParameterLocation.QUERY, "page", "", false, "2"),
                mapping("/lang", HttpParameterLocation.HEADER, "X-Lang", "", false, "\"zh-CN\"")
        ));

        PreparedHttpToolRequest missing = mapper.map(config, objectMapper.readTree("{}"));
        PreparedHttpToolRequest explicitNull = mapper.map(config,
                objectMapper.readTree("{\"page\":null,\"lang\":null}"));

        assertThat(missing.queryValues()).containsEntry("page", List.of("2"));
        assertThat(missing.headers()).containsEntry("X-Lang", "zh-CN");
        assertThat(explicitNull.queryValues()).containsEntry("page", List.of("2"));
        assertThat(explicitNull.headers()).containsEntry("X-Lang", "zh-CN");
    }

    @Test
    void rejectsRequiredValueWithoutDefaultUsingFixedChineseMessage() throws Exception {
        HttpToolConfig config = config(objectSchema("id", "string"), List.of(
                mapping("/id", HttpParameterLocation.QUERY, "id", "", true, "")
        ));

        assertThatThrownBy(() -> mapper.map(config, objectMapper.readTree("{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("必填参数缺失");
    }

    @Test
    void validatesEffectiveInputAgainstFullSchemaAfterApplyingDefaults() throws Exception {
        HttpToolConfig config = config("""
                {"type":"object","required":["count"],"properties":{"count":{"type":"integer","minimum":1}}}
                """, List.of(mapping("/count", HttpParameterLocation.QUERY, "count", "", true, "1")));

        assertThat(mapper.map(config, objectMapper.readTree("{}"))
                .queryValues()).containsEntry("count", List.of("1"));
        assertThatThrownBy(() -> mapper.map(config, objectMapper.readTree("{\"count\":0}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("输入");
    }

    @Test
    void mapsEscapedPointersScalarsAndScalarArray() throws Exception {
        String schema = """
                {"type":"object","properties":{
                  "order/id":{"type":"string"},
                  "enabled":{"type":"boolean"},
                  "tags":{"type":"array","items":{"type":"integer"}}
                }}
                """;
        HttpToolConfig config = new HttpToolConfig(TENANT_ID, TOOL_ID, HttpToolMethod.GET,
                "https://api.example.test/items/{id}", schema, List.of(
                mapping("/order~1id", HttpParameterLocation.PATH, "id", "", true, ""),
                mapping("/enabled", HttpParameterLocation.HEADER, "X-Enabled", "", false, ""),
                mapping("/tags", HttpParameterLocation.QUERY, "tag", "", false, "")
        ), Map.of(), Duration.ofSeconds(5));

        PreparedHttpToolRequest request = mapper.map(config,
                objectMapper.readTree("{\"order/id\":\"A/1\",\"enabled\":true,\"tags\":[1,2]}"));

        assertThat(request.pathValues()).containsEntry("id", "A/1");
        assertThat(request.headers()).containsEntry("X-Enabled", "true");
        assertThat(request.queryValues()).containsEntry("tag", List.of("1", "2"));
    }

    @Test
    void buildsNestedBodyAndDoesNotShareMutableJsonNodes() throws Exception {
        String schema = """
                {"type":"object","properties":{
                  "order":{"type":"object","properties":{"details":{"type":"object"}}},
                  "items":{"type":"array","items":{"type":"string"}}
                }}
                """;
        HttpToolConfig config = config(schema, List.of(
                mapping("/order/details", HttpParameterLocation.BODY, "", "/payload/details", false, ""),
                mapping("/items", HttpParameterLocation.BODY, "", "/payload/items", false, "")
        ));
        JsonNode input = objectMapper.readTree("{\"order\":{\"details\":{\"note\":\"before\"}},\"items\":[\"one\"]}");

        PreparedHttpToolRequest request = mapper.map(config, input);
        ((com.fasterxml.jackson.databind.node.ObjectNode) input.at("/order/details")).put("note", "after");
        ((com.fasterxml.jackson.databind.node.ArrayNode) input.at("/items")).add("two");

        assertThat(request.body()).isEqualTo(objectMapper.readTree(
                "{\"payload\":{\"details\":{\"note\":\"before\"},\"items\":[\"one\"]}}"));

        PreparedHttpToolRequest copied = new PreparedHttpToolRequest(Map.of(), Map.of(), Map.of(), request.body());
        ((com.fasterxml.jackson.databind.node.ObjectNode) request.body().at("/payload/details")).put("note", "changed");
        assertThat(copied.body().at("/payload/details/note").asText()).isEqualTo("before");
    }

    @Test
    void buildsArrayContainersForNumericBodyPointerTokens() throws Exception {
        HttpToolConfig config = config(objectSchema("name", "string"), List.of(
                mapping("/name", HttpParameterLocation.BODY, "", "/payload/items/0/name", true, "")
        ));

        PreparedHttpToolRequest request = mapper.map(config, objectMapper.readTree("{\"name\":\"first\"}"));

        assertThat(request.body()).isEqualTo(objectMapper.readTree(
                "{\"payload\":{\"items\":[{\"name\":\"first\"}]}}"));
    }

    @Test
    void omitsOptionalValuesWithoutDefaults() throws Exception {
        HttpToolConfig config = config(objectSchema("optional", "string"), List.of(
                mapping("/optional", HttpParameterLocation.QUERY, "optional", "", false, "")
        ));

        PreparedHttpToolRequest request = mapper.map(config, objectMapper.readTree("{}"));

        assertThat(request.pathValues()).isEmpty();
        assertThat(request.queryValues()).isEmpty();
        assertThat(request.headers()).isEmpty();
        assertThat(request.body().isNull()).isTrue();
    }

    @Test
    void appliesDefaultThroughArrayIndexPointer() throws Exception {
        String schema = """
                {"type":"object","properties":{"items":{"type":"array","items":{
                  "type":"object","properties":{"quantity":{"type":"integer"}}
                }}}}
                """;
        HttpToolConfig config = config(schema, List.of(
                mapping("/items/0/quantity", HttpParameterLocation.QUERY, "quantity", "", true, "1")
        ));

        PreparedHttpToolRequest request = mapper.map(config, objectMapper.readTree("{\"items\":[{}]}"));

        assertThat(request.queryValues()).containsEntry("quantity", List.of("1"));
    }

    @Test
    void appliesDefaultValidatedThroughAllOfRootDefinition() throws Exception {
        String schema = """
                {"type":"object","$defs":{"positive":{"type":"integer","minimum":1}},"properties":{
                  "count":{"allOf":[{"$ref":"#/$defs/positive"}]}
                }}
                """;
        HttpToolConfig config = config(schema, List.of(
                mapping("/count", HttpParameterLocation.QUERY, "count", "", true, "2")
        ));

        PreparedHttpToolRequest request = mapper.map(config, objectMapper.readTree("{}"));

        assertThat(request.queryValues()).containsEntry("count", List.of("2"));
    }

    @Test
    void mapsConstScalarArrayWithoutStringifyingObjects() throws Exception {
        String schema = """
                {"type":"object","properties":{"tags":{"const":["a","b"]}}}
                """;
        HttpToolConfig config = config(schema, List.of(
                mapping("/tags", HttpParameterLocation.QUERY, "tag", "", true, "")
        ));

        PreparedHttpToolRequest request = mapper.map(config,
                objectMapper.readTree("{\"tags\":[\"a\",\"b\"]}"));

        assertThat(request.queryValues()).containsEntry("tag", List.of("a", "b"));
    }

    @Test
    void treatsNumericPointerTokenAsPropertyWhenSchemaNodeIsObject() throws Exception {
        String schema = """
                {"type":"object","properties":{"container":{"type":"object","properties":{
                  "0":{"type":"string"}
                }}}}
                """;
        HttpToolConfig config = config(schema, List.of(
                mapping("/container/0", HttpParameterLocation.QUERY, "value", "", true, "\"fallback\"")
        ));

        PreparedHttpToolRequest request = mapper.map(config, objectMapper.readTree("{}"));

        assertThat(request.queryValues()).containsEntry("value", List.of("fallback"));
    }

    private static HttpToolConfig config(String schema, List<HttpParameterMapping> mappings) {
        return new HttpToolConfig(TENANT_ID, TOOL_ID, HttpToolMethod.POST,
                "https://api.example.test/items", schema, mappings, Map.of(), Duration.ofSeconds(5));
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
}
