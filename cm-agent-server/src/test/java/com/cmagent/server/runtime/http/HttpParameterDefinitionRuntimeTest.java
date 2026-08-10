package com.cmagent.server.runtime.http;

import com.cmagent.core.domain.HttpParameterDataType;
import com.cmagent.core.domain.HttpParameterDefinition;
import com.cmagent.core.domain.HttpParameterLocation;
import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpParameterDefinitionRuntimeTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpToolConfigValidator validator = new HttpToolConfigValidator(objectMapper);
    private final HttpToolInputMapper inputMapper = new HttpToolInputMapper(objectMapper, validator);

    @Test
    void 空参数定义生成封闭空对象Schema并映射无参数请求() throws Exception {
        String schema = validator.compileParameterDefinitions(
                List.of(), HttpToolMethod.GET, "https://api.example.test/tools"
        );
        HttpToolConfig config = new HttpToolConfig(
                TENANT_ID,
                TOOL_ID,
                HttpToolMethod.GET,
                "https://api.example.test/tools",
                List.of(),
                Map.of(),
                Duration.ofSeconds(3)
        );

        validator.validate(config);
        PreparedHttpToolRequest prepared = inputMapper.map(config, objectMapper.createObjectNode());

        assertThat(objectMapper.readTree(schema).path("properties").isEmpty()).isTrue();
        assertThat(objectMapper.readTree(schema).path("additionalProperties").asBoolean()).isFalse();
        assertThat(prepared.pathValues()).isEmpty();
        assertThat(prepared.queryValues()).isEmpty();
        assertThat(prepared.headers()).isEmpty();
        assertThat(prepared.body().isNull()).isTrue();
        assertThatThrownBy(() -> inputMapper.map(config, objectMapper.readTree("{\"unexpected\":true}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("工具输入不符合 parameters 生成的 inputSchema");
    }

    @Test
    void 扁平参数定义生成Schema并映射PathQuery和根数组Body() throws Exception {
        List<HttpParameterDefinition> parameters = List.of(
                parameter("orderId", "", "orderId", HttpParameterDataType.STRING,
                        HttpParameterLocation.PATH, true, ""),
                parameter("page", "", "page", HttpParameterDataType.INTEGER,
                        HttpParameterLocation.QUERY, false, "1"),
                parameter("payload", "", "payload", HttpParameterDataType.ARRAY,
                        HttpParameterLocation.BODY_ROOT, true, ""),
                parameter("payloadItem", "payload", "", HttpParameterDataType.OBJECT,
                        null, false, ""),
                parameter("p1", "payloadItem", "p1", HttpParameterDataType.STRING,
                        null, true, ""),
                parameter("enabled", "payloadItem", "enabled", HttpParameterDataType.BOOLEAN,
                        null, false, "true")
        );
        String schema = validator.compileParameterDefinitions(
                parameters, HttpToolMethod.POST, "https://api.example.test/orders/{orderId}"
        );
        HttpToolConfig config = new HttpToolConfig(
                TENANT_ID,
                TOOL_ID,
                HttpToolMethod.POST,
                "https://api.example.test/orders/{orderId}",
                parameters,
                Map.of(),
                Duration.ofSeconds(3)
        );

        validator.validate(config);
        PreparedHttpToolRequest prepared = inputMapper.map(
                config,
                objectMapper.readTree("""
                        {"orderId":"A1001","payload":[{"p1":"v1"}]}
                        """)
        );

        assertThat(prepared.pathValues()).containsExactly(Map.entry("orderId", "A1001"));
        assertThat(prepared.queryValues()).containsExactly(Map.entry("page", List.of("1")));
        assertThat(prepared.body()).isEqualTo(objectMapper.readTree("""
                [{"p1":"v1","enabled":true}]
                """));
        assertThat(objectMapper.readTree(schema).path("properties").path("payload").path("type").asText())
                .isEqualTo("array");
    }

    @Test
    void 数组必须只有一个匿名元素节点() {
        List<HttpParameterDefinition> parameters = List.of(
                parameter("items", "", "items", HttpParameterDataType.ARRAY,
                        HttpParameterLocation.BODY_ROOT, true, ""),
                parameter("item", "items", "namedItem", HttpParameterDataType.STRING,
                        null, false, "")
        );

        assertThatThrownBy(() -> validator.compileParameterDefinitions(
                parameters, HttpToolMethod.POST, "https://api.example.test/items"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ARRAY 元素节点 name 必须为空");
    }

    @Test
    void BodyRoot不能与普通Body同时配置() {
        List<HttpParameterDefinition> parameters = List.of(
                parameter("payload", "", "payload", HttpParameterDataType.OBJECT,
                        HttpParameterLocation.BODY_ROOT, true, ""),
                parameter("metadata", "", "metadata", HttpParameterDataType.OBJECT,
                        HttpParameterLocation.BODY, false, "")
        );

        assertThatThrownBy(() -> validator.compileParameterDefinitions(
                parameters, HttpToolMethod.POST, "https://api.example.test/items"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("BODY_ROOT 不能与 BODY 参数同时配置");
    }

    @Test
    void Path参数必须与Url占位符同名且完整匹配() {
        List<HttpParameterDefinition> parameters = List.of(
                parameter("id", "", "id", HttpParameterDataType.STRING,
                        HttpParameterLocation.PATH, true, "")
        );

        assertThatThrownBy(() -> validator.compileParameterDefinitions(
                parameters, HttpToolMethod.GET, "https://api.example.test/items/{itemId}"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PATH 参数必须完整匹配 URL 占位符");
    }

    private static HttpParameterDefinition parameter(
            String id,
            String parentId,
            String name,
            HttpParameterDataType dataType,
            HttpParameterLocation location,
            boolean required,
            String defaultValue
    ) {
        return new HttpParameterDefinition(
                id, parentId, name, dataType, location, id, required, defaultValue, "",
                List.of(), null, null, null, null, null, null, false
        );
    }
}
