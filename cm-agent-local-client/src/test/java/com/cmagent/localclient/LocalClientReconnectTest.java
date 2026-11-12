package com.cmagent.localclient;

import com.cmagent.core.domain.LocalClientDevice;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class LocalClientReconnectTest {
    @Test void 通用操作迟到回执被拒后继续领取后续任务(@TempDir Path directory) throws Exception {
        var server = server(0, new CountDownLatch(0));
        var nextCount = new java.util.concurrent.atomic.AtomicInteger();
        var resultCount = new java.util.concurrent.atomic.AtomicInteger();
        var continued = new CountDownLatch(1);
        UUID operationId = UUID.randomUUID();
        server.removeContext("/api/local-client/bootstrap/project-tasks/next");
        server.createContext("/api/local-client/bootstrap/project-tasks/next", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.removeContext("/api/local-client/bootstrap/operations/next");
        server.createContext("/api/local-client/bootstrap/operations/next", exchange -> {
            if (nextCount.incrementAndGet() == 1) {
                byte[] body = ("{\"operationId\":\"" + operationId + "\",\"category\":\"NETWORK\","
                        + "\"version\":1,\"request\":{\"method\":\"GET\",\"url\":\"http://127.0.0.1:"
                        + server.getAddress().getPort() + "/health\",\"headers\":{},\"bodyBase64\":\"\","
                        + "\"timeoutSeconds\":5,\"responseLimit\":1024}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(204, -1);
                continued.countDown();
            }
            exchange.close();
        });
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/local-client/bootstrap/operations/" + operationId + "/result", exchange -> {
            resultCount.incrementAndGet();
            exchange.sendResponseHeaders(409, -1);
            exchange.close();
        });
        server.start();
        var store = new DeviceIdentityStore(directory, new ObjectMapper(), new SecureRandom(), Optional.empty());
        var identity = store.create(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "测试设备",
                LocalClientDevice.Platform.WINDOWS, "fixture-only".toCharArray());
        identity = store.confirm(identity, UUID.randomUUID(), UUID.randomUUID(), "owner");
        var running = new AtomicBoolean(true);
        var failure = new AtomicReference<Throwable>();
        try (var client = new LocalClientServerClient(new ObjectMapper(), new SecureRandom());
             var executor = new LocalClientTaskExecutor(new ObjectMapper())) {
            var confirmed = identity;
            Thread loop = new Thread(() -> {
                try { LocalClientApplication.consumeTasks(confirmed, executor, client, running); }
                catch (InterruptedException expectedOnShutdown) { Thread.currentThread().interrupt(); }
                catch (Throwable unexpected) { failure.set(unexpected); }
            });
            loop.start();
            try {
                assertThat(continued.await(12, TimeUnit.SECONDS)).isTrue();
                assertThat(resultCount.get()).isEqualTo(1);
                assertThat(failure.get()).isNull();
            } finally {
                running.set(false);
                loop.interrupt();
                loop.join(5000);
            }
            assertThat(loop.isAlive()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test void 被拒收的项目回执不重发且继续领取后续任务(@TempDir Path directory) throws Exception {
        var server = server(0, new CountDownLatch(0));
        var nextCount = new java.util.concurrent.atomic.AtomicInteger();
        var resultCount = new java.util.concurrent.atomic.AtomicInteger();
        var continued = new CountDownLatch(1);
        UUID taskId = UUID.randomUUID();
        server.removeContext("/api/local-client/bootstrap/project-tasks/next");
        server.createContext("/api/local-client/bootstrap/project-tasks/next", exchange -> {
            if (nextCount.incrementAndGet() == 1) {
                byte[] body = ("{\"taskId\":\"" + taskId + "\",\"category\":\"PROJECT_SELECT_ROOT\","
                        + "\"version\":1,\"request\":{}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(204, -1);
                continued.countDown();
            }
            exchange.close();
        });
        server.createContext("/api/local-client/bootstrap/project-tasks/" + taskId + "/result", exchange -> {
            resultCount.incrementAndGet();
            exchange.sendResponseHeaders(409, -1);
            exchange.close();
        });
        server.start();
        var store = new DeviceIdentityStore(directory, new ObjectMapper(), new SecureRandom(), Optional.empty());
        var identity = store.create(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "测试设备",
                LocalClientDevice.Platform.WINDOWS, "fixture-only".toCharArray());
        identity = store.confirm(identity, UUID.randomUUID(), UUID.randomUUID(), "owner");
        var running = new AtomicBoolean(true);
        var failure = new AtomicReference<Throwable>();
        try (var client = new LocalClientServerClient(new ObjectMapper(), new SecureRandom());
             var executor = new LocalClientTaskExecutor(new ObjectMapper())) {
            var confirmed = identity;
            Thread loop = new Thread(() -> {
                try { LocalClientApplication.consumeTasks(confirmed, executor, client, running); }
                catch (InterruptedException expectedOnShutdown) { Thread.currentThread().interrupt(); }
                catch (Throwable unexpected) { failure.set(unexpected); }
            });
            loop.start();
            try {
                assertThat(continued.await(12, TimeUnit.SECONDS)).isTrue();
                assertThat(resultCount.get()).isEqualTo(1);
                assertThat(failure.get()).isNull();
            } finally {
                running.set(false);
                loop.interrupt();
                loop.join(5000);
            }
            assertThat(loop.isAlive()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test void 服务中断后客户端保留执行器并恢复签名轮询(@TempDir Path directory) throws Exception {
        var first = new CountDownLatch(1);
        HttpServer server = server(0, first);
        int port = server.getAddress().getPort();
        server.start();
        var store = new DeviceIdentityStore(directory, new ObjectMapper(), new SecureRandom(), Optional.empty());
        var identity = store.create(URI.create("http://127.0.0.1:" + port), "测试设备",
                LocalClientDevice.Platform.WINDOWS, "fixture-only".toCharArray());
        identity = store.confirm(identity, UUID.randomUUID(), UUID.randomUUID(), "owner");
        var running = new AtomicBoolean(true);
        var failure = new AtomicReference<Throwable>();
        try (var client = new LocalClientServerClient(new ObjectMapper(), new SecureRandom());
             var executor = new LocalClientTaskExecutor(new ObjectMapper())) {
            var confirmed = identity;
            Thread loop = new Thread(() -> {
                try { LocalClientApplication.consumeTasks(confirmed, executor, client, running); }
                catch (InterruptedException expectedOnShutdown) { Thread.currentThread().interrupt(); }
                catch (Throwable unexpected) { failure.set(unexpected); }
            });
            loop.start();
            try {
                assertThat(first.await(10, TimeUnit.SECONDS)).isTrue();
                server.stop(0);
                // 跨过一次空轮询和断线退避，证明客户端不是在连接失败时退出。
                Thread.sleep(5500);
                assertThat(loop.isAlive()).isTrue();
                assertThat(failure.get()).isNull();
                var recovered = new CountDownLatch(1);
                server = server(port, recovered);
                server.start();
                assertThat(recovered.await(12, TimeUnit.SECONDS)).isTrue();
                assertThat(failure.get()).isNull();
            } finally {
                running.set(false);
                loop.interrupt();
                loop.join(5000);
            }
            assertThat(loop.isAlive()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(int port, CountDownLatch claimed) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/local-client/bootstrap/challenges", exchange -> {
            byte[] body = ("{\"challengeId\":\"" + UUID.randomUUID() + "\",\"nonce\":\""
                    + Base64.getEncoder().encodeToString(new byte[32]) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        for (String endpoint : new String[]{"/api/local-client/bootstrap/project-tasks/next",
                "/api/local-client/bootstrap/operations/next"}) {
            server.createContext(endpoint, exchange -> {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                claimed.countDown();
            });
        }
        return server;
    }
}
