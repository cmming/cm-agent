package com.cmagent.core.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelConfigTest {

    private static final UUID ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void 规范化文本并接受带路径的Http端点() {
        ModelConfig config = new ModelConfig(
                ID, TENANT_ID, ModelProviderType.OPENAI_COMPATIBLE,
                " 订单模型 ", " https://models.example.test/v1 ", " qwen-plus ", true
        );

        assertThat(config.displayName()).isEqualTo("订单模型");
        assertThat(config.baseUrl()).isEqualTo("https://models.example.test/v1");
        assertThat(config.modelName()).isEqualTo("qwen-plus");
    }

    @Test
    void 拒绝非Http端点和包含用户信息的地址() {
        assertThatThrownBy(() -> new ModelConfig(
                ID, TENANT_ID, ModelProviderType.DASHSCOPE_NATIVE,
                "模型", "file:///tmp/model", "qwen-plus", true
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTP(S)");

        assertThatThrownBy(() -> new ModelConfig(
                ID, TENANT_ID, ModelProviderType.DASHSCOPE_NATIVE,
                "模型", "https://user:password@models.example.test/v1", "qwen-plus", true
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTP(S)");
    }
}
