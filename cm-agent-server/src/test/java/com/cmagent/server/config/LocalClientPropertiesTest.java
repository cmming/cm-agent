package com.cmagent.server.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalClientPropertiesTest {

    @Test
    void 启用时接受显式配置的非回环HTTP和HTTPS规范来源() {
        assertThatCode(() -> properties("http://192.168.0.159:8080").validate())
                .doesNotThrowAnyException();
        assertThatCode(() -> properties("https://agent.example.test").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void 规范来源仍拒绝凭据路径查询和片段() {
        assertThatThrownBy(() -> properties("http://user:secret@192.168.0.159:8080").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> properties("http://192.168.0.159:8080/api").validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> properties("http://192.168.0.159:8080?debug=true").validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> properties("http://192.168.0.159:8080#fragment").validate())
                .isInstanceOf(IllegalStateException.class);
    }

    private static LocalClientProperties properties(String audience) {
        var properties = new LocalClientProperties();
        properties.setEnabled(true);
        properties.setAudience(audience);
        return properties;
    }
}
