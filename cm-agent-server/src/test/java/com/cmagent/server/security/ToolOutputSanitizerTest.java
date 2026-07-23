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

    @Test
    void redactsPrefixedStructuredSecretKeysWithoutMaskingOrdinaryFields() throws Exception {
        String output = sanitizer.sanitize("""
                {
                  "X-Api-Key":"api-secret",
                  "X-Auth-Token":"auth-secret",
                  "serviceApiKey":"service-secret",
                  "databaseSecret":"database-secret",
                  "vendorAccessToken":"access-secret",
                  "serviceRefreshToken":"refresh-secret",
                  "tokenCount":12,
                  "monkey":"visible"
                }
                """, java.util.List.of());

        var json = new ObjectMapper().readTree(output);
        assertThat(json.path("X-Api-Key").asText()).isEqualTo("<已脱敏>");
        assertThat(json.path("X-Auth-Token").asText()).isEqualTo("<已脱敏>");
        assertThat(json.path("serviceApiKey").asText()).isEqualTo("<已脱敏>");
        assertThat(json.path("databaseSecret").asText()).isEqualTo("<已脱敏>");
        assertThat(json.path("vendorAccessToken").asText()).isEqualTo("<已脱敏>");
        assertThat(json.path("serviceRefreshToken").asText()).isEqualTo("<已脱敏>");
        assertThat(json.path("tokenCount").asInt()).isEqualTo(12);
        assertThat(json.path("monkey").asText()).isEqualTo("visible");
    }
}
