package com.cmagent.server.runtime.http;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
public class HttpToolUrlPolicy {
    private static final String REJECTED_MESSAGE = "HTTP 目标地址不允许";
    private static final byte[] IPV6_DOCUMENTATION_PREFIX = {0x20, 0x01, 0x0d, (byte) 0xb8};

    private final HttpToolProperties properties;
    private final HostAddressResolver addressResolver;

    @Autowired
    public HttpToolUrlPolicy(HttpToolProperties properties) {
        this(properties, host -> List.of(InetAddress.getAllByName(host)));
    }

    public HttpToolUrlPolicy(HttpToolProperties properties, HostAddressResolver addressResolver) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.addressResolver = Objects.requireNonNull(addressResolver, "addressResolver 不能为空");
    }

    public URI validate(URI uri) {
        if (uri == null || uri.isOpaque() || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw rejected();
        }
        String scheme = normalizeScheme(uri.getScheme());
        validateSchemeAndPort(scheme, uri.getPort());
        String host = canonicalHost(uri);
        if (isLocalhost(host) || !isAllowedHost(host)) {
            throw rejected();
        }
        List<InetAddress> addresses = resolve(host);
        if (addresses.isEmpty() || addresses.stream().anyMatch(address -> !isPublicAddress(address))) {
            throw rejected();
        }
        return canonicalUri(uri, scheme, host);
    }

    public boolean hasSameOrigin(URI first, URI second) {
        try {
            return origin(first).equals(origin(second));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private Origin origin(URI uri) {
        if (uri == null || uri.isOpaque() || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw rejected();
        }
        String scheme = normalizeScheme(uri.getScheme());
        validateSchemeAndPort(scheme, uri.getPort());
        int effectivePort = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        return new Origin(scheme, canonicalHost(uri), effectivePort);
    }

    private String normalizeScheme(String scheme) {
        if (scheme == null) {
            throw rejected();
        }
        return scheme.toLowerCase(Locale.ROOT);
    }

    private void validateSchemeAndPort(String scheme, int port) {
        if ("https".equals(scheme)) {
            if (port != -1 && port != 443) {
                throw rejected();
            }
            return;
        }
        if ("http".equals(scheme) && properties.isAllowHttp()
                && (port == -1 || (port >= 1 && port <= 65_535))) {
            return;
        }
        throw rejected();
    }

    private String canonicalHost(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            String authority = uri.getRawAuthority();
            if (authority == null || authority.isBlank() || authority.indexOf('@') >= 0
                    || authority.indexOf(':') >= 0 || authority.indexOf('%') >= 0
                    || authority.indexOf('[') >= 0 || authority.indexOf(']') >= 0) {
                throw rejected();
            }
            host = authority;
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isBlank()) {
            throw rejected();
        }
        try {
            return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private boolean isAllowedHost(String host) {
        for (String configured : properties.getAllowedHosts()) {
            String pattern = canonicalAllowedHost(configured);
            if (pattern == null) {
                continue;
            }
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1);
                if (host.endsWith(suffix) && host.length() > suffix.length()) {
                    return true;
                }
            } else if (host.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String canonicalAllowedHost(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String value = configured.trim();
        boolean wildcard = value.startsWith("*.");
        if (value.indexOf('*', wildcard ? 1 : 0) >= 0) {
            return null;
        }
        String domain = wildcard ? value.substring(2) : value;
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        try {
            String ascii = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (ascii.isBlank() || ascii.indexOf('.') < 0) {
                return null;
            }
            return wildcard ? "*." + ascii : ascii;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<InetAddress> resolve(String host) {
        try {
            List<InetAddress> resolved = addressResolver.resolve(host);
            return resolved == null ? List.of() : List.copyOf(resolved);
        } catch (UnknownHostException | RuntimeException exception) {
            throw rejected();
        }
    }

    private boolean isLocalhost(String host) {
        return "localhost".equals(host) || host.endsWith(".localhost");
    }

    private URI canonicalUri(URI original, String scheme, String host) {
        StringBuilder value = new StringBuilder(scheme).append("://").append(host);
        if (original.getPort() != -1) {
            value.append(':').append(original.getPort());
        }
        if (original.getRawPath() != null) {
            value.append(original.getRawPath());
        }
        if (original.getRawQuery() != null) {
            value.append('?').append(original.getRawQuery());
        }
        try {
            return URI.create(URI.create(value.toString()).toASCIIString());
        } catch (IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address inet4Address) {
            return isPublicIpv4(inet4Address.getAddress());
        }
        if (address instanceof Inet6Address inet6Address) {
            return isPublicIpv6(inet6Address.getAddress());
        }
        return false;
    }

    private boolean isPublicIpv4(byte[] bytes) {
        long value = ((long) bytes[0] & 0xff) << 24
                | ((long) bytes[1] & 0xff) << 16
                | ((long) bytes[2] & 0xff) << 8
                | ((long) bytes[3] & 0xff);
        return !inIpv4Range(value, "0.0.0.0", 8)
                && !inIpv4Range(value, "10.0.0.0", 8)
                && !inIpv4Range(value, "100.64.0.0", 10)
                && value != ipv4Value("100.100.100.200")
                && !inIpv4Range(value, "127.0.0.0", 8)
                && !inIpv4Range(value, "169.254.0.0", 16)
                && !inIpv4Range(value, "172.16.0.0", 12)
                && !inIpv4Range(value, "192.0.0.0", 24)
                && !inIpv4Range(value, "192.0.2.0", 24)
                && !inIpv4Range(value, "192.168.0.0", 16)
                && !inIpv4Range(value, "198.18.0.0", 15)
                && !inIpv4Range(value, "198.51.100.0", 24)
                && !inIpv4Range(value, "203.0.113.0", 24)
                && !inIpv4Range(value, "224.0.0.0", 4)
                && !inIpv4Range(value, "240.0.0.0", 4);
    }

    private boolean isPublicIpv6(byte[] bytes) {
        if ((bytes[0] & 0xe0) != 0x20) {
            return false;
        }
        return !hasIpv6Prefix(bytes, new byte[]{0x20, 0x01, 0x00}, 23)
                && !hasIpv6Prefix(bytes, new byte[]{0x20, 0x02}, 16)
                && !hasIpv6Prefix(bytes, IPV6_DOCUMENTATION_PREFIX, 32)
                && !hasIpv6Prefix(bytes, new byte[]{0x3f, (byte) 0xff, 0x00}, 20);
    }

    private static boolean hasIpv6Prefix(byte[] address, byte[] prefix, int prefixLength) {
        int fullBytes = prefixLength / 8;
        for (int index = 0; index < fullBytes; index++) {
            if (address[index] != prefix[index]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits) & 0xff;
        return (address[fullBytes] & mask) == (prefix[fullBytes] & mask);
    }

    private static boolean inIpv4Range(long value, String networkAddress, int prefixLength) {
        long mask = prefixLength == 0 ? 0 : 0xffff_ffffL << (32 - prefixLength) & 0xffff_ffffL;
        return (value & mask) == (ipv4Value(networkAddress) & mask);
    }

    private static long ipv4Value(String value) {
        String[] parts = value.split("\\.");
        return Long.parseLong(parts[0]) << 24
                | Long.parseLong(parts[1]) << 16
                | Long.parseLong(parts[2]) << 8
                | Long.parseLong(parts[3]);
    }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException(REJECTED_MESSAGE);
    }

    private record Origin(String scheme, String host, int port) {
    }
}
