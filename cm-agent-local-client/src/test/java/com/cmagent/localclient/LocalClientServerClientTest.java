package com.cmagent.localclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalClientServerClientTest {
    @Test
    void 项目任务使用独立签名端点(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var nextPath = new java.util.concurrent.atomic.AtomicReference<String>();
        var protocol = new java.util.concurrent.atomic.AtomicReference<String>();
        var capability = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/api/local-client/bootstrap/challenges", exchange -> {
            byte[] body = ("{\"challengeId\":\"" + UUID.randomUUID() + "\",\"nonce\":\""
                    + java.util.Base64.getEncoder().encodeToString(new byte[32]) + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/local-client/bootstrap/project-tasks/next", exchange -> {
            nextPath.set(exchange.getRequestURI().getPath());
            protocol.set(exchange.getRequestHeaders().getFirst("X-CM-Client-Project-Protocol"));
            capability.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try (var client = new LocalClientServerClient(new ObjectMapper(), new SecureRandom())) {
            var store = new DeviceIdentityStore(directory, new ObjectMapper(), new SecureRandom(),
                    java.util.Optional.empty());
            var identity = store.create(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "测试机", com.cmagent.core.domain.LocalClientDevice.Platform.WINDOWS,
                    "fixture-pass".toCharArray());
            identity = store.confirm(identity, UUID.randomUUID(), UUID.randomUUID(), "owner");
            assertThat(client.nextProjectTask(identity)).isEmpty();
            assertThat(nextPath.get()).isEqualTo("/api/local-client/bootstrap/project-tasks/next");
            assertThat(protocol.get()).isEqualTo("5");
            assertThat(capability.get()).isEqualTo("{\"selectionProtocol\":1}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 项目任务按服务端一次性taskId解码并回报(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var resultPath = new java.util.concurrent.atomic.AtomicReference<String>();
        var resultStatus = new AtomicInteger(204);
        server.createContext("/api/local-client/bootstrap/challenges", exchange -> {
            byte[] body = ("{\"challengeId\":\"" + UUID.randomUUID() + "\",\"nonce\":\""
                    + java.util.Base64.getEncoder().encodeToString(new byte[32]) + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        UUID taskId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        server.createContext("/api/local-client/bootstrap/project-tasks/next", exchange -> {
            byte[] body = ("{\"taskId\":\"" + taskId + "\",\"operationId\":\"" + operationId
                    + "\",\"category\":\"PROJECT_SELECT_ROOT\",\"version\":1,\"request\":{}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/local-client/bootstrap/project-tasks/" + taskId + "/result", exchange -> {
            resultPath.set(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(resultStatus.get(), -1);
            exchange.close();
        });
        server.start();
        try (var client = new LocalClientServerClient(new ObjectMapper(), new SecureRandom())) {
            var store = new DeviceIdentityStore(directory, new ObjectMapper(), new SecureRandom(),
                    java.util.Optional.empty());
            var identity = store.create(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "测试机", com.cmagent.core.domain.LocalClientDevice.Platform.WINDOWS,
                    "fixture-pass".toCharArray());
            identity = store.confirm(identity, UUID.randomUUID(), UUID.randomUUID(), "owner");
            var task = client.nextProjectTask(identity).orElseThrow();
            assertThat(task.taskId()).isEqualTo(taskId);
            assertThat(task.operationId()).isEqualTo(operationId);
            client.reportProjectTask(identity, task, "SUCCEEDED",
                    new ObjectMapper().createObjectNode(), "");
            assertThat(resultPath.get())
                    .isEqualTo("/api/local-client/bootstrap/project-tasks/" + taskId + "/result");
            resultStatus.set(409);
            var confirmed = identity;
            assertThatThrownBy(() -> client.reportProjectTask(confirmed, task, "FAILED",
                    new ObjectMapper().createObjectNode(), ""))
                    .isInstanceOf(LocalClientServerClient.ProjectResultRejectedException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 设备不可用时提示核对而执行后回报失败提示结果可能未知(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var challengeStatus = new AtomicInteger(404);
        server.createContext("/api/local-client/bootstrap/challenges", exchange -> {
            byte[] body = (challengeStatus.get() == 404 ? "伪造的私密信息"
                    : "{\"challengeId\":\"" + UUID.randomUUID() + "\",\"nonce\":\""
                    + java.util.Base64.getEncoder().encodeToString(new byte[32]) + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(challengeStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/local-client/bootstrap/operations/next", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try (var client = new LocalClientServerClient(new ObjectMapper(), new SecureRandom())) {
            var store = new DeviceIdentityStore(directory, new ObjectMapper(), new SecureRandom(), java.util.Optional.empty());
            var identity = store.create(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "测试机",
                    com.cmagent.core.domain.LocalClientDevice.Platform.WINDOWS, "fixture-pass".toCharArray());
            identity = store.confirm(identity, UUID.randomUUID(), UUID.randomUUID(), "owner");
            var bound = identity;
            assertThatThrownBy(() -> client.nextTask(bound))
                    .isInstanceOf(LocalClientServerClient.DeviceUnavailableException.class)
                    .hasMessageContaining("已撤销").hasMessageContaining("控制台")
                    .satisfies(error -> assertThat(error.getMessage()).doesNotContain("私密信息"));
            var task = new LocalClientServerClient.Task(UUID.randomUUID(), "COMMAND", 1, new ObjectMapper().createObjectNode());
            assertThatThrownBy(() -> client.reportTask(bound, task, "SUCCEEDED", new ObjectMapper().createObjectNode(), ""))
                    .isInstanceOf(LocalClientServerClient.DeviceUnavailableException.class)
                    .hasMessageContaining("可能已发生").hasMessageContaining("勿自动重试")
                    .satisfies(error -> assertThat(error.getMessage()).doesNotContain("私密信息"));
            challengeStatus.set(200);
            assertThatThrownBy(() -> client.nextTask(bound))
                    .isInstanceOf(LocalClientServerClient.ClientFailureException.class)
                    .hasMessageContaining("签名").hasMessageContaining("已撤销");
            challengeStatus.set(429);
            assertThatThrownBy(() -> client.nextTask(bound))
                    .isInstanceOf(LocalClientServerClient.ChallengeRateLimitedException.class);
            assertThatThrownBy(() -> client.reportTask(bound, task, "SUCCEEDED", new ObjectMapper().createObjectNode(), ""))
                    .isInstanceOf(LocalClientServerClient.ClientFailureException.class)
                    .hasMessageContaining("可能已发生").hasMessageContaining("勿自动重试");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 浏览器登记前的未找到表示等待其他错误仍然失败() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new AtomicInteger(404);
        server.createContext("/api/local-client/bootstrap/pairings/", exchange -> {
            if (status.get() == 200) {
                byte[] body = ("{\"state\":\"PENDING\",\"expiresAt\":\"2026-11-02T00:00:00Z\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(status.get(), -1);
            }
            exchange.close();
        });
        server.start();
        try (var client = new LocalClientServerClient(new ObjectMapper(), new SecureRandom())) {
            var origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            assertThat(client.pairingStatus(origin, UUID.randomUUID())).isEmpty();
            status.set(200);
            assertThat(client.pairingStatus(origin, UUID.randomUUID()).orElseThrow().expiresAt())
                    .isEqualTo("2026-11-02T00:00:00Z");
            status.set(500);
            assertThatThrownBy(() -> client.pairingStatus(origin, UUID.randomUUID()))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("配对状态查询失败");
        } finally {
            server.stop(0);
        }
    }
}
