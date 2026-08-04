package com.cmagent.server.runtime.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpToolUrlPolicyTest {
    private HttpToolProperties properties;
    private HostAddressResolver publicResolver;

    @BeforeEach
    /**
     * 准备每个测试用例共享的前置数据。
     */
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
    /**
     * 验证系统允许 {@code CanonicalExactAndControlledSubdomainHosts} 场景。
     *
     * @param value 测试输入值
     */
    void allowsCanonicalExactAndControlledSubdomainHosts(String value) {
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, publicResolver);

        assertThat(policy.validate(URI.create(value))).isNotNull();
    }

    @Test
    /**
     * 验证系统会返回 {@code AsciiCanonicalUriForUnicodeIdnBeforeHttpClientUsesIt}。
     */
    void returnsAsciiCanonicalUriForUnicodeIdnBeforeHttpClientUsesIt() {
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, publicResolver);

        URI canonical = policy.validate(URI.create("https://BÜCHER.example/订单?q=中文"));

        assertThat(canonical.getHost()).isEqualTo("xn--bcher-kva.example");
        assertThat(canonical.toASCIIString()).doesNotContain("BÜCHER").doesNotContain("中文");
    }

    @Test
    /**
     * 验证或支持 {@code comparesCanonicalSchemeHostAndEffectivePortForRedirectOrigin} 所描述的测试场景。
     */
    void comparesCanonicalSchemeHostAndEffectivePortForRedirectOrigin() {
        properties.setAllowHttp(true);
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, publicResolver);

        assertThat(policy.hasSameOrigin(
                URI.create("https://API.EXAMPLE.COM./start"),
                URI.create("https://api.example.com:443/final"))).isTrue();
        assertThat(policy.hasSameOrigin(
                URI.create("http://api.example.com/start"),
                URI.create("http://API.EXAMPLE.COM.:80/final"))).isTrue();
        assertThat(policy.hasSameOrigin(
                URI.create("https://api.example.com/start"),
                URI.create("https://child.trusted.example/final"))).isFalse();
        assertThat(policy.hasSameOrigin(
                URI.create("https://api.example.com/start"),
                URI.create("https://other.example.com/final"))).isFalse();
        assertThat(policy.hasSameOrigin(
                URI.create("http://api.example.com:8080/start"),
                URI.create("http://api.example.com:8081/final"))).isFalse();
        assertThat(policy.hasSameOrigin(
                URI.create("http://api.example.com:80/start"),
                URI.create("https://api.example.com:443/final"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/orders",
            "https://trusted.example/orders",
            "https://trusted.example.evil.test/orders",
            "https://evil-trusted.example/orders",
            "https://api.example.com.evil.test/orders"
    })
    /**
     * 验证 {@code HostAllowlistSuffixConfusion} 异常场景会被正确拒绝。
     *
     * @param value 测试输入值
     */
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
    /**
     * 验证 {@code UnsafeUriComponents} 异常场景会被正确拒绝。
     *
     * @param value 测试输入值
     */
    void rejectsUnsafeUriComponents(String value) {
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, publicResolver);

        assertRejected(policy, value);
    }

    @Test
    /**
     * 验证系统允许 {@code HttpOnlyWhenExplicitlyEnabledForControlledTestPort} 场景。
     */
    void allowsHttpOnlyWhenExplicitlyEnabledForControlledTestPort() {
        properties.setAllowHttp(true);
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, publicResolver);

        assertThat(policy.validate(URI.create("http://api.example.com:18080/orders"))).isNotNull();
        assertThat(policy.validate(URI.create("http://api.example.com/orders"))).isNotNull();
    }

    @Test
    /**
     * 验证 {@code WhenAllowlistIsEmptyBeforeDnsResolution} 异常场景会被正确拒绝。
     */
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
    /**
     * 验证 {@code NonPublicReservedAndMetadataAddresses} 异常场景会被正确拒绝。
     *
     * @param address 测试辅助方法使用的 address 参数
     */
    void rejectsNonPublicReservedAndMetadataAddresses(String address) throws Exception {
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, ignored -> List.of(address(address)));

        assertRejected(policy, "https://api.example.com/orders");
    }

    @ParameterizedTest
    @MethodSource("specialIpv6Bytes")
    /**
     * 验证 {@code SpecialUseIpv6UsingRawPrefixBytes} 异常场景会被正确拒绝。
     *
     * @param rawAddress 测试辅助方法使用的 rawAddress 参数
     */
    void rejectsSpecialUseIpv6UsingRawPrefixBytes(byte[] rawAddress) throws Exception {
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, ignored -> List.of(
                InetAddress.getByAddress(rawAddress)));

        assertRejected(policy, "https://api.example.com/orders");
    }

    @Test
    /**
     * 验证系统允许 {@code ExplicitGlobalUnicastIpv6OutsideSpecialRanges} 场景。
     */
    void allowsExplicitGlobalUnicastIpv6OutsideSpecialRanges() throws Exception {
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, ignored -> List.of(
                InetAddress.getByAddress(hex("26064700000000000000000000001111"))));

        assertThat(policy.validate(URI.create("https://api.example.com/orders"))).isNotNull();
    }

    @Test
    /**
     * 验证 {@code DnsAnswerWhenAnyAddressIsNotPublic} 异常场景会被正确拒绝。
     */
    void rejectsDnsAnswerWhenAnyAddressIsNotPublic() throws Exception {
        HttpToolUrlPolicy policy = new HttpToolUrlPolicy(properties, ignored -> List.of(
                address("93.184.216.34"), address("127.0.0.1")
        ));

        assertRejected(policy, "https://api.example.com/orders");
    }

    @Test
    /**
     * 验证 {@code MissingAndFailedDnsAnswersWithoutLeakingUrl} 异常场景会被正确拒绝。
     */
    void rejectsMissingAndFailedDnsAnswersWithoutLeakingUrl() {
        HttpToolUrlPolicy emptyPolicy = new HttpToolUrlPolicy(properties, ignored -> List.of());
        HttpToolUrlPolicy failedPolicy = new HttpToolUrlPolicy(properties, ignored -> {
            throw new UnknownHostException("api.example.com?token=不得泄露");
        });

        assertRejected(emptyPolicy, "https://api.example.com/orders?token=不得泄露");
        assertRejected(failedPolicy, "https://api.example.com/orders?token=不得泄露");
    }

    /**
     * 验证或支持 {@code assertRejected} 所描述的测试场景。
     *
     * @param policy 测试辅助方法使用的 policy 参数
     * @param value 测试输入值
     */
    private static void assertRejected(HttpToolUrlPolicy policy, String value) {
        assertThatThrownBy(() -> policy.validate(URI.create(value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HTTP 目标地址不允许")
                .hasMessageNotContaining("不得泄露")
                .hasMessageNotContaining(value);
    }

    /**
     * 验证或支持 {@code address} 所描述的测试场景。
     *
     * @param value 测试输入值
     */
    private static InetAddress address(String value) throws UnknownHostException {
        return InetAddress.getByName(value);
    }

    /**
     * 验证或支持 {@code specialIpv6Bytes} 所描述的测试场景。
     */
    private static Stream<byte[]> specialIpv6Bytes() {
        return Stream.of(
                hex("00000000000000000000ffffc0000201"),
                hex("0064ff9b000000000000000000000001"),
                hex("0064ff9b000100000000000000000001"),
                hex("01000000000000000000000000000001"),
                hex("20010000000000000000000000000001"),
                hex("20010002000000000000000000000001"),
                hex("20010020000000000000000000000001"),
                hex("20010db8000000000000000000000001"),
                hex("20020000000000000000000000000001"),
                hex("3fff0000000000000000000000000001"),
                hex("fc000000000000000000000000000001"),
                hex("fe800000000000000000000000000001"),
                hex("ff020000000000000000000000000001"),
                hex("00000000000000000000000000000000")
        );
    }

    /**
     * 验证或支持 {@code hex} 所描述的测试场景。
     *
     * @param value 测试输入值
     */
    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }
}
