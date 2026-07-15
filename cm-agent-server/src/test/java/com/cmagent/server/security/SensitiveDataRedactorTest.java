package com.cmagent.server.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataRedactorTest {

    @Test
    void redactsTokensCredentialsAndConnectionStrings() {
        String source = "Bearer eyJhbGciOiJIUzI1NiJ9.demo password=unit-test-password "
                + "apiKey=unit-test-api-key jdbc:postgresql://unit-user:unit-password@db.example/cm_agent";

        String redacted = new SensitiveDataRedactor().redact(source);

        assertThat(redacted)
                .contains("<已脱敏>")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9.demo", "unit-test-password", "unit-test-api-key", "unit-user:unit-password");
    }
}
