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
    @Test void 代次握手拒绝明文回环和带用户信息的来源() throws Exception {
        assertThatThrownBy(() -> LocalClientServerClient.requireFenceHttps(URI.create("http://127.0.0.1:8080")))
                .isInstanceOf(LocalClientServerClient.ClientFailureException.class);
        assertThatThrownBy(() -> LocalClientServerClient.requireFenceHttps(URI.create("https://user:secret@example.test")))
                .isInstanceOf(LocalClientServerClient.ClientFailureException.class)
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("secret"));
        assertThatThrownBy(() -> LocalClientServerClient.requireFenceHttps(URI.create("https://example.test/path")))
                .isInstanceOf(LocalClientServerClient.ClientFailureException.class);
        assertThat(LocalClientServerClient.requireFenceHttps(URI.create("https://example.test:8443")))
                .isEqualTo(URI.create("https://example.test:8443"));
    }

    @Test void 明文来源不能申请代次确认挑战(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var challenges = new AtomicInteger();
        server.createContext("/api/local-client/bootstrap/challenges", exchange -> {
            challenges.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try (var client = new LocalClientServerClient(new ObjectMapper(), new SecureRandom())) {
            var store = new DeviceIdentityStore(directory, new ObjectMapper(), new SecureRandom(),
                    java.util.Optional.empty());
            var created = store.create(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "测试机", com.cmagent.core.domain.LocalClientDevice.Platform.WINDOWS,
                    "fixture-pass".toCharArray());
            var identity = store.confirm(created, UUID.randomUUID(), UUID.randomUUID(), "owner");
            assertThatThrownBy(() -> client.acknowledgeProjectFence(identity, UUID.randomUUID(), 1))
                    .isInstanceOf(LocalClientServerClient.ClientFailureException.class)
                    .hasMessageContaining("HTTPS");
            assertThat(challenges).hasValue(0);
        } finally { server.stop(0); }
    }

    @Test void 代次确认使用真实TLS和绑定正文的设备签名(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        var keystore = directory.resolve("fixture.p12");
        String keytool = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process generated = new ProcessBuilder(keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
                "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "fixture-only",
                "-keypass", "fixture-only", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-noprompt").redirectErrorStream(true).start();
        assertThat(generated.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(generated.exitValue()).isZero();
        var keys = java.security.KeyStore.getInstance("PKCS12");
        try (var input = java.nio.file.Files.newInputStream(keystore)) { keys.load(input, "fixture-only".toCharArray()); }
        var km = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, "fixture-only".toCharArray());
        var tm = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        var serverTls = javax.net.ssl.SSLContext.getInstance("TLS");
        serverTls.init(km.getKeyManagers(), null, new SecureRandom());
        var clientTls = javax.net.ssl.SSLContext.getInstance("TLS");
        clientTls.init(null, tm.getTrustManagers(), new SecureRandom());
        var server = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(serverTls));
        UUID challenge = UUID.randomUUID(), run = UUID.randomUUID(), root = UUID.randomUUID();
        byte[] nonce = new byte[32];
        var identityRef = new java.util.concurrent.atomic.AtomicReference<DeviceIdentityStore.StoredIdentity>();
        var verified = new java.util.concurrent.atomic.AtomicBoolean();
        var verifiedCurrent = new java.util.concurrent.atomic.AtomicBoolean();
        var rootIdentity = new java.util.concurrent.atomic.AtomicReference<>("a".repeat(64));
        var epoch = new java.util.concurrent.atomic.AtomicLong(2);
        var phase = new java.util.concurrent.atomic.AtomicReference<>("RECOVERING");
        var acknowledged = new java.util.concurrent.atomic.AtomicBoolean(false);
        var responseCode = new AtomicInteger(204);
        server.createContext("/api/local-client/bootstrap/challenges", exchange -> {
            byte[] body = ("{\"challengeId\":\"" + challenge + "\",\"nonce\":\""
                    + java.util.Base64.getEncoder().encodeToString(nonce) + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/api/local-client/bootstrap/project-runs/" + run + "/fence-ack", exchange -> {
            int result = 401;
            try {
                var identity = identityRef.get();
                byte[] body = exchange.getRequestBody().readAllBytes();
                var metadata = identity.metadata();
                var proof = new com.cmagent.core.security.DeviceRequestProof(challenge, nonce, metadata.deviceId(),
                        metadata.tenantId(), metadata.ownerId(), "https://127.0.0.1:" + server.getAddress().getPort(),
                        "POST", exchange.getRequestURI().getRawPath(), java.security.MessageDigest.getInstance("SHA-256").digest(body));
                boolean valid = new String(body, java.nio.charset.StandardCharsets.US_ASCII).equals("{\"epoch\":" + epoch.get() + "}")
                        && challenge.toString().equals(exchange.getRequestHeaders().getFirst("X-CM-Challenge-Id"))
                        && metadata.deviceId().toString().equals(exchange.getRequestHeaders().getFirst("X-CM-Device-Id"))
                        && "5".equals(exchange.getRequestHeaders().getFirst("X-CM-Client-Project-Protocol"))
                        && com.cmagent.core.security.DeviceProofSignatures.verify(proof, identity.keyPair().getPublic(),
                            java.util.Base64.getDecoder().decode(exchange.getRequestHeaders().getFirst("X-CM-Signature")));
                verified.set(valid);
                if (valid) {
                    result = responseCode.get();
                    if (result == 204) acknowledged.set(true);
                }
            } catch (Exception failure) { result = 500; }
            exchange.sendResponseHeaders(result, -1); exchange.close();
        });
        server.createContext("/api/local-client/bootstrap/project-runs/" + run + "/fence-current", exchange -> {
            try {
                var identity = identityRef.get();
                byte[] body = exchange.getRequestBody().readAllBytes();
                var metadata = identity.metadata();
                var proof = new com.cmagent.core.security.DeviceRequestProof(challenge, nonce, metadata.deviceId(),
                        metadata.tenantId(), metadata.ownerId(), "https://127.0.0.1:" + server.getAddress().getPort(),
                        "POST", exchange.getRequestURI().getRawPath(), java.security.MessageDigest.getInstance("SHA-256").digest(body));
                boolean valid = new String(body, java.nio.charset.StandardCharsets.US_ASCII).equals("{\"fenceProtocol\":1}")
                        && challenge.toString().equals(exchange.getRequestHeaders().getFirst("X-CM-Challenge-Id"))
                        && com.cmagent.core.security.DeviceProofSignatures.verify(proof, identity.keyPair().getPublic(),
                            java.util.Base64.getDecoder().decode(exchange.getRequestHeaders().getFirst("X-CM-Signature")));
                verifiedCurrent.set(valid);
                if (!valid) { exchange.sendResponseHeaders(401, -1); exchange.close(); return; }
                byte[] response = ("{\"runId\":\"" + run + "\",\"rootId\":\"" + root
                        + "\",\"rootIdentity\":\"" + rootIdentity.get()
                        + "\",\"epoch\":" + epoch.get() + ",\"phase\":\"" + phase.get()
                        + "\",\"acknowledged\":" + acknowledged.get() + "}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (Exception failure) { exchange.sendResponseHeaders(500, -1); }
            exchange.close();
        });
        server.start();
        try {
            var store = new DeviceIdentityStore(directory.resolve("identity"), new ObjectMapper(), new SecureRandom(),
                    java.util.Optional.empty());
            var created = store.create(URI.create("https://127.0.0.1:" + server.getAddress().getPort()),
                    "测试机", com.cmagent.core.domain.LocalClientDevice.Platform.WINDOWS, "fixture-only".toCharArray());
            var identity = store.confirm(created, UUID.randomUUID(), UUID.randomUUID(), "owner");
            identityRef.set(identity);
            try (var untrusted = new LocalClientServerClient(new ObjectMapper(), new SecureRandom())) {
                assertThatThrownBy(() -> untrusted.acknowledgeProjectFence(identity, run, 2))
                        .isInstanceOf(java.io.IOException.class);
            }
            var http = java.net.http.HttpClient.newBuilder().sslContext(clientTls)
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
            try (var client = new LocalClientServerClient(new ObjectMapper(), new SecureRandom(), http)) {
                var current = client.currentProjectFence(identity, run);
                assertThat(current.rootId()).isEqualTo(root);
                assertThat(current.rootIdentity()).isEqualTo("a".repeat(64));
                assertThat(current.phase()).isEqualTo("RECOVERING");
                assertThat(verifiedCurrent).isTrue();
                rootIdentity.set("invalid");
                assertThatThrownBy(() -> client.currentProjectFence(identity, run))
                        .isInstanceOf(LocalClientServerClient.ClientFailureException.class);
                client.acknowledgeProjectFence(identity, run, 2);
                assertThat(verified).isTrue();
                responseCode.set(409);
                assertThatThrownBy(() -> client.acknowledgeProjectFence(identity, run, 2))
                        .isInstanceOf(LocalClientServerClient.ClientFailureException.class);
                // 使用测试根夹具和真实 TLS，验证先落盘、签名确认、旧代次拒绝及恢复确认重试。
                responseCode.set(204); rootIdentity.set("a".repeat(64));
                epoch.set(1); phase.set("EXECUTING"); acknowledged.set(false);
                var journal = new ClientProjectFenceJournal(directory.resolve("fence"));
                journal.initializeStore();
                var handshake = new ClientProjectFenceHandshake(client, (bound, id) -> {
                    if (!id.equals(root)) throw new java.io.IOException("根不匹配");
                    return "a".repeat(64);
                }, journal);
                handshake.synchronize(identity, run);
                assertThat(acknowledged).isTrue();
                assertThat(handshake.execute(identity, run, root, 1, () -> "executed")).isEqualTo("executed");
                var lostJournal = new ClientProjectFenceJournal(directory.resolve("lost-fence"));
                lostJournal.initializeStore();
                var lostHandshake = new ClientProjectFenceHandshake(client, (bound, id) -> "a".repeat(64), lostJournal);
                assertThatThrownBy(() -> lostHandshake.synchronize(identity, run)).isInstanceOf(java.io.IOException.class);
                epoch.set(2); phase.set("RECOVERING"); acknowledged.set(false);
                handshake.synchronize(identity, run);
                assertThat(acknowledged).isTrue();
                assertThatThrownBy(() -> handshake.execute(identity, run, root, 1, () -> "bad"))
                        .isInstanceOf(java.io.IOException.class);
                assertThat(handshake.executeTask(identity, run, root, 2, "RECOVERING",
                        "PROJECT_COMMAND_STOP", () -> "stopped")).isEqualTo("stopped");
                assertThatThrownBy(() -> handshake.executeTask(identity, run, root, 2, "RECOVERING",
                        "PROJECT_COMMAND_RUN", () -> "replayed"))
                        .isInstanceOf(java.io.IOException.class);
                handshake.synchronize(identity, run);
            }
        } finally { server.stop(0); }
    }

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
            assertThat(capability.get()).isEqualTo("{\"selectionProtocol\":1,\"fenceProtocol\":1}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 非运行级项目任务显式携带零代次时仍可执行() {
        var task = new LocalClientServerClient.Task(UUID.randomUUID(), null,
                "PROJECT_SELECT_ROOT", 1, new ObjectMapper().createObjectNode(),
                null, null, 0L, null);

        org.assertj.core.api.Assertions.assertThat(task.fencedProjectTask()).isFalse();
        org.assertj.core.api.Assertions.assertThatCode(task::requireValidFence).doesNotThrowAnyException();
    }

    @Test void 项目任务端点先返回代次确认要求(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        UUID runId = UUID.randomUUID(), rootId = UUID.randomUUID();
        server.createContext("/api/local-client/bootstrap/challenges", exchange -> {
            byte[] body = ("{\"challengeId\":\"" + UUID.randomUUID() + "\",\"nonce\":\""
                    + java.util.Base64.getEncoder().encodeToString(new byte[32]) + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/local-client/bootstrap/project-tasks/next", exchange -> {
            byte[] body = ("{\"runId\":\"" + runId + "\",\"rootId\":\"" + rootId
                    + "\",\"rootIdentity\":\"" + "a".repeat(64)
                    + "\",\"epoch\":1,\"phase\":\"EXECUTING\",\"acknowledged\":false}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, body.length);
            exchange.getResponseBody().write(body);
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
            var bound = identity;
            assertThatThrownBy(() -> client.nextProjectTask(bound))
                    .isInstanceOfSatisfying(LocalClientServerClient.FenceRequiredException.class,
                            required -> assertThat(required.runId()).isEqualTo(runId));
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
