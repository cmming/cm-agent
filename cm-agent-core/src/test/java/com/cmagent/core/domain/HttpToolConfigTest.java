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
    void GET拒绝BODY且集合防御性复制() {
        var mappings = new ArrayList<HttpParameterMapping>();
        mappings.add(new HttpParameterMapping("/order/no", HttpParameterLocation.PATH,
                "orderNo", "", true, "\"A100\""));
        var config = new HttpToolConfig(TENANT, TOOL, HttpToolMethod.GET,
                "https://api.example.com/orders/{orderNo}", "{\"type\":\"object\"}",
                mappings, Map.of("Authorization", "order-token"), Duration.ofSeconds(5));

        mappings.clear();

        assertThat(config.parameterMappings()).hasSize(1);
        assertThatThrownBy(() -> new HttpToolConfig(TENANT, TOOL, HttpToolMethod.GET,
                "https://api.example.com", "{\"type\":\"object\"}",
                List.of(new HttpParameterMapping("/x", HttpParameterLocation.BODY,
                        "", "/x", false, "")), Map.of(), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 参数映射按位置校验目标且识别默认值() {
        var mapping = new HttpParameterMapping("/page", HttpParameterLocation.QUERY,
                "page", "", false, "1");

        assertThat(mapping.hasDefaultValue()).isTrue();
        assertThatThrownBy(() -> new HttpParameterMapping("page", HttpParameterLocation.QUERY,
                "page", "", false, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourcePointer 必须是 JSON Pointer");
        assertThatThrownBy(() -> new HttpParameterMapping("/body", HttpParameterLocation.BODY,
                "body", "/body", false, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("BODY 参数必须只提供 targetPointer");
    }
}
