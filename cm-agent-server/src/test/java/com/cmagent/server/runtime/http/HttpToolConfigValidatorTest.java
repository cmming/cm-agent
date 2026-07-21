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
