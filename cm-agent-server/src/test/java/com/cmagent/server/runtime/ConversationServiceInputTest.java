package com.cmagent.server.runtime;

import com.cmagent.server.security.SensitiveDataRedactor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationServiceInputTest {
    @Test
    void 本机客户端运行使用原始URL而持久化输入保持脱敏() {
        String original = "请访问 http://127.0.0.1:8080/actuator/health";
        String persisted = new SensitiveDataRedactor().redact(original);

        assertThat(persisted)
                .contains("<已脱敏>")
                .doesNotContain("127.0.0.1");
        assertThat(ConversationService.modelInputFor(original, persisted, true)).isEqualTo(original);
        assertThat(ConversationService.modelInputFor(original, persisted, false)).isEqualTo(persisted);
    }
}
