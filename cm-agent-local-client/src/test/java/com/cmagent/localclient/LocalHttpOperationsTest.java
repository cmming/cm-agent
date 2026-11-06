package com.cmagent.localclient;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalHttpOperationsTest {
    @Test
    void 本机网络请求限制响应大小且不自动跟随重定向() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/large", exchange -> {
            byte[] body = "abcdef".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/large");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try (var operations = new LocalHttpOperations()) {
            URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var result = operations.request(root.resolve("/large"), "GET", Map.of(), new byte[0],
                    Duration.ofSeconds(3), 3);
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).containsExactly('a', 'b', 'c');
            assertThat(result.truncated()).isTrue();
            byte[] copy = result.body();
            copy[0] = 0;
            assertThat(result.body()).containsExactly('a', 'b', 'c');

            var redirect = operations.request(root.resolve("/redirect"), "GET", Map.of(), new byte[0],
                    Duration.ofSeconds(3), 10);
            assertThat(redirect.statusCode()).isEqualTo(302);
            assertThat(redirect.location()).isEqualTo("/large");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 响应正文停滞时超时并提示远端结果可能未知() throws Exception {
        var release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var threads = Executors.newSingleThreadExecutor();
        server.setExecutor(threads);
        server.createContext("/slow", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write('a');
                exchange.getResponseBody().flush();
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var operations = new LocalHttpOperations()) {
            URI target = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow");
            assertThatThrownBy(() -> operations.request(target, "POST", Map.of(), new byte[]{1},
                    Duration.ofMillis(300), 10))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("结果可能未知");
        } finally {
            release.countDown();
            server.stop(0);
            threads.shutdownNow();
        }
    }
}
