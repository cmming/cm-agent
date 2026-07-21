package com.cmagent.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolOutputSanitizerTest {

    private final ToolOutputSanitizer sanitizer = new ToolOutputSanitizer(new ObjectMapper());

    @Test
    void recursivelyRedactsJsonSecretsUrlsAndExceptionDetails() {
        String output = sanitizer.sanitize("""
                {"access_token":"token-value","cookie":"cookie-value","safe":"https://private.example.test/a",
                "nested":{"client_secret":"secret-value","trace":"java.lang.IllegalStateException: hidden"}}
                """, java.util.List.of("known-secret"));

        assertThat(output).doesNotContain("token-value", "cookie-value", "secret-value", "https://", "IllegalStateException");
    }

    @Test
    void redactsQuotedTextKeysAuthorizationUrlsAndSuppressedStackDetails() {
        String output = sanitizer.sanitize("""
                payload={"access_token":"token-value","client_secret":"secret-value"}
                Authorization: Bearer auth-value
                endpoint=https://private.example.test/a
                Caused by: java.lang.IllegalStateException: hidden
                """, java.util.List.of());

        assertThat(output).doesNotContain("token-value", "secret-value", "auth-value", "https://", "Caused by", "IllegalStateException");
    }
}
