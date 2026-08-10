package com.cmagent.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpToolConfigTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TOOL = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Test
    void 参数定义集合使用防御性复制() {
        var parameters = new ArrayList<HttpParameterDefinition>();
        parameters.add(parameter("orderNo", HttpParameterLocation.PATH));
        var config = new HttpToolConfig(
                TENANT, TOOL, HttpToolMethod.GET, "https://api.example.com/orders/{orderNo}",
                parameters, Map.of("Authorization", "secret/order-token"), Duration.ofSeconds(5)
        );

        parameters.clear();

        assertThat(config.parameters()).hasSize(1);
    }

    @Test
    void GET拒绝Body参数且允许空参数定义() {
        assertThatThrownBy(() -> new HttpToolConfig(
                TENANT, TOOL, HttpToolMethod.GET, "https://api.example.com",
                List.of(parameter("payload", HttpParameterLocation.BODY)), Map.of(), Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("GET 工具不能配置 BODY 参数");

        HttpToolConfig parameterless = new HttpToolConfig(
                TENANT, TOOL, HttpToolMethod.GET, "https://api.example.com",
                List.of(), Map.of(), Duration.ofSeconds(1)
        );
        assertThat(parameterless.parameters()).isEmpty();
    }

    @Test
    void 静态敏感请求头必须使用受限Secret引用() {
        assertThatThrownBy(() -> new HttpToolConfig(
                TENANT, TOOL, HttpToolMethod.POST, "https://api.example.com",
                List.of(parameter("payload", HttpParameterLocation.BODY)),
                Map.of("Authorization", "实际密钥值"), Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("secretHeaders 必须使用 secret/ 开头的引用");
        assertThatThrownBy(() -> new HttpToolConfig(
                TENANT, TOOL, HttpToolMethod.POST, "https://api.example.com",
                List.of(parameter("payload", HttpParameterLocation.BODY)),
                Map.of("Authorization", "secret/含中文"), Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("secretHeaders 必须使用 secret/ 开头的引用");
    }

    private static HttpParameterDefinition parameter(String name, HttpParameterLocation location) {
        return new HttpParameterDefinition(
                name, "", name, HttpParameterDataType.STRING, location, "", true,
                "", "", List.of(), null, null, null, null, null, null, false
        );
    }
}
