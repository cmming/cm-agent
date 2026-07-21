package com.cmagent.server.runtime.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpToolUrlPolicyTest {
    private HttpToolProperties properties;
    private HostAddressResolver publicResolver;

    @BeforeEach
    void setUp() throws Exception {
        properties = new HttpToolProperties();
        properties.setAllowedHosts(Set.of("api.example.com", "*.trusted.example", "xn--bcher-kva.example"));
        InetAddress publicAddress = address("93.184.216.34");
        publicResolver = ignored -> List.of(publicAddress);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.example.com/orders",
            "https://API.EXAMPLE.COM./orders",
            "https://child.trusted.example/orders",
            "https://deep.child.trusted.example/orders",
            "https://bücher.example/orders"
    })
    void allowsCanonicalExactAndControlledSubdomainHosts(String value) {
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, publicResolver);

        assertThat(policy.validate(URI.create(value))).isNotNull();
    }

    @Test
    void returnsAsciiCanonicalUriForUnicodeIdnBeforeHttpClientUsesIt() {
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, publicResolver);

        URI canonical = policy.validate(URI.create("https://BÜCHER.example/订单?q=中文"));

        assertThat(canonical.getHost()).isEqualTo("xn--bcher-kva.example");
        assertThat(canonical.toASCIIString()).doesNotContain("BÜCHER").doesNotContain("中文");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/orders",
            "https://trusted.example/orders",
            "https://trusted.example.evil.test/orders",
            "https://evil-trusted.example/orders",
            "https://api.example.com.evil.test/orders"
    })
    void rejectsHostAllowlistSuffixConfusion(String value) {
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, publicResolver);

        assertRejected(policy, value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api.example.com/orders",
            "ftp://api.example.com/orders",
            "https://user@api.example.com/orders",
            "https://api.example.com/orders#fragment",
            "https://api.example.com:444/orders",
            "https://api.example.com:0/orders",
            "https://api.example.com:65536/orders",
            "https://localhost/orders"
    })
    void rejectsUnsafeUriComponents(String value) {
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, publicResolver);

        assertRejected(policy, value);
    }

    @Test
    void allowsHttpOnlyWhenExplicitlyEnabledForControlledTestPort() {
        properties.setAllowHttp(true);
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, publicResolver);

        assertThat(policy.validate(URI.create("http://api.example.com:18080/orders"))).isNotNull();
        assertThat(policy.validate(URI.create("http://api.example.com/orders"))).isNotNull();
    }

    @Test
    void rejectsWhenAllowlistIsEmptyBeforeDnsResolution() {
        properties.setAllowedHosts(Set.of());
        int[] resolutions = {0};
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, host -> {
            resolutions[0]++;
            return List.of(address("93.184.216.34"));
        });

        assertRejected(policy, "https://api.example.com/private?token=不得泄露");
        assertThat(resolutions[0]).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "10.0.0.1", "100.64.0.1", "100.100.100.200", "127.0.0.1",
            "169.254.169.254", "172.16.0.1", "192.0.2.1", "192.168.0.1", "198.18.0.1",
            "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fd00::1", "fe80::1", "ff02::1", "2001:db8::1"
    })
    void rejectsNonPublicReservedAndMetadataAddresses(String address) throws Exception {
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, ignored -> List.of(address(address)));

        assertRejected(policy, "https://api.example.com/orders");
    }

    @Test
    void rejectsDnsAnswerWhenAnyAddressIsNotPublic() throws Exception {
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, ignored -> List.of(
                address("93.184.216.34"), address("127.0.0.1")
        ));

        assertRejected(policy, "https://api.example.com/orders");
    }

    @Test
    void rejectsMissingAndFailedDnsAnswersWithoutLeakingUrl() {
        HttpToolUrlPolicy emptyPolicy = new HttpToolUrlPolicy(properties, ignored -> List.of());
        HttpToolUrlPolicy failedPolicy = new HttpToolUrlPolicy(properties, ignored -> {
            throw new UnknownHostException("api.example.com?token=不得泄露");
        });

        assertRejected(emptyPolicy, "https://api.example.com/orders?token=不得泄露");
        assertRejected(failedPolicy, "https://api.example.com/orders?token=不得泄露");
    }

    private static void assertRejected(HttpToolUrlPolicy policy, String value) {
        assertThatThrownBy(() -> policy.validate(URI.create(value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HTTP 目标地址不允许")
                .hasMessageNotContaining("不得泄露")
                .hasMessageNotContaining(value);
    }

    private static InetAddress address(String value) throws UnknownHostException {
        return InetAddress.getByName(value);
    }
}
