package com.cmagent.server.runtime;

import com.cmagent.server.config.LocalProjectProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LocalProjectRequestBoundaryTest {
    @Test void 精确回环同源显式请求可访问而跨源重绑定和转发伪装被拒绝() {
        var boundary = new LocalProjectRequestBoundary(properties());
        assertTrue(boundary.permits(request()));
        var cli = request(); cli.removeHeader("Origin"); cli.removeHeader("Sec-Fetch-Site");
        assertTrue(boundary.permits(cli));
        var cross = request(); cross.removeHeader("Origin"); cross.addHeader("Origin", "https://attacker.invalid");
        assertFalse(boundary.permits(cross));
        var rebinding = request(); rebinding.removeHeader("Host"); rebinding.addHeader("Host", "attacker.invalid:8080");
        assertFalse(boundary.permits(rebinding));
        var remote = request(); remote.setRemoteAddr("192.0.2.1"); remote.addHeader("X-Forwarded-For", "127.0.0.1");
        assertFalse(boundary.permits(remote));
        var proxied = request(); proxied.addHeader("X-Forwarded-For", "192.0.2.1");
        assertFalse(boundary.permits(proxied));
        var wildcardBind = request(); wildcardBind.setLocalAddr("192.0.2.2");
        assertFalse(boundary.permits(wildcardBind));
        var noAction = request(); noAction.removeHeader("X-CM-Agent-Local-Project");
        assertFalse(boundary.permits(noAction));
        var fetch = request(); fetch.removeHeader("Sec-Fetch-Site"); fetch.addHeader("Sec-Fetch-Site", "cross-site");
        assertFalse(boundary.permits(fetch));
    }

    @Test void 启用策略不接受远端通配用户信息或带路径的源及无界期限() {
        for (String origin : List.of("*", "http://example.invalid", "http://127.0.0.1:8080/", "http://u@localhost:8080", "http://localhost:8080?q=1", "http://127.0.0.2:8080")) {
            var properties = properties(); properties.setAllowedOrigins(List.of(origin));
            assertThrows(IllegalStateException.class, properties::validate, origin);
        }
        var properties = properties(); properties.setSelectionTtl(Duration.ofMinutes(6));
        assertThrows(IllegalStateException.class, properties::validate);
        properties.setEnabled(false); assertDoesNotThrow(properties::validate);
        assertFalse(properties.toString().contains(properties.getStateDirectory()));
    }

    @Test void 显式允许非回环时仍要求精确同源和本地行动头() {
        var properties = properties(); properties.setAllowNonLoopback(true);
        properties.setAllowedOrigins(List.of("http://192.0.2.20:8080"));
        var boundary = new LocalProjectRequestBoundary(properties);
        var remote = new MockHttpServletRequest();
        remote.setRemoteAddr("192.0.2.30"); remote.setLocalAddr("192.0.2.20");
        remote.addHeader("Host", "192.0.2.20:8080"); remote.addHeader("Origin", "http://192.0.2.20:8080");
        remote.addHeader("X-CM-Agent-Local-Project", "1"); remote.addHeader("Sec-Fetch-Site", "same-origin");
        assertTrue(boundary.permits(remote));
        var cross = new MockHttpServletRequest(); cross.setRemoteAddr("192.0.2.30"); cross.setLocalAddr("192.0.2.20");
        cross.addHeader("Host", "192.0.2.20:8080"); cross.addHeader("Origin", "https://attacker.invalid");
        cross.addHeader("X-CM-Agent-Local-Project", "1"); cross.addHeader("Sec-Fetch-Site", "same-origin");
        assertFalse(boundary.permits(cross));
        var forwarded = new MockHttpServletRequest(); forwarded.setRemoteAddr("192.0.2.30"); forwarded.setLocalAddr("192.0.2.20");
        forwarded.addHeader("Host", "192.0.2.20:8080"); forwarded.addHeader("Origin", "http://192.0.2.20:8080");
        forwarded.addHeader("X-CM-Agent-Local-Project", "1"); forwarded.addHeader("Sec-Fetch-Site", "same-origin");
        forwarded.addHeader("X-Forwarded-For", "127.0.0.1");
        assertFalse(boundary.permits(forwarded));
        var wildcard = properties(); wildcard.setAllowNonLoopback(true);
        wildcard.setAllowedOrigins(List.of("http://0.0.0.0:8080"));
        assertThrows(IllegalStateException.class, () -> new LocalProjectRequestBoundary(wildcard));
    }

    private static LocalProjectProperties properties() {
        var result = new LocalProjectProperties(); result.setEnabled(true); result.setNodeId("node-1");
        result.setStateDirectory(Path.of(System.getProperty("java.io.tmpdir")).resolve("private-project-state").toAbsolutePath().toString());
        result.setAllowedOrigins(List.of("http://127.0.0.1:8080")); return result;
    }
    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1"); request.setLocalAddr("127.0.0.1");
        request.addHeader("Host", "127.0.0.1:8080"); request.addHeader("Origin", "http://127.0.0.1:8080");
        request.addHeader("X-CM-Agent-Local-Project", "1"); request.addHeader("Sec-Fetch-Site", "same-origin"); return request;
    }
}
