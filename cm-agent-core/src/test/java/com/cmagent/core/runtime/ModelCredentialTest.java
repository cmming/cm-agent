package com.cmagent.core.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCredentialTest {

    @Test
    /**
     * 验证 {@code BlankApiKey} 异常场景会被正确拒绝。
     */
    void rejectsBlankApiKey() {
        assertThatThrownBy(() -> new ModelCredential("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("模型 API Key 不能为空");
    }

    @Test
    /**
     * 验证或支持 {@code neverPrintsApiKey} 所描述的测试场景。
     */
    void neverPrintsApiKey() {
        ModelCredential credential = new ModelCredential("unit-test-model-key");
        assertThat(credential.apiKey()).isEqualTo("unit-test-model-key");
        assertThat(credential.toString()).isEqualTo("ModelCredential[apiKey=<已脱敏>]")
                .doesNotContain("unit-test-model-key");
    }
}
