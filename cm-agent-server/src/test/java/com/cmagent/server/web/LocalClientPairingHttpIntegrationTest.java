package com.cmagent.server.web;

import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.security.DevicePairingSignatures;
import com.cmagent.core.security.DeviceProofSignatures;
import com.cmagent.core.security.DeviceRequestProof;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 以回环 HTTP 和临时设备密钥验证配对与一次性签名协议，不连接真实模型。 */
@SpringBootTest(classes = CmAgentServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "cm-agent.local-client.enabled=true",
            "cm-agent.local-client.audience=http://127.0.0.1:8080",
            // 本测试只验证本机客户端协议，显式关闭依赖 JDBC 与 Windows 原生能力的本地项目装配。
            "cm-agent.local-projects.enabled=false",
            "cm-agent.workspace-context.enabled=false",
            "cm-agent.workspace-context.root-directory=${java.io.tmpdir}/cm-agent-reference-test",
            "cm-agent.workspace-context.imports.root-directory=${java.io.tmpdir}/cm-agent-import-test",
            "cm-agent.workspace-context.imports.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "cm-agent.workspace-context.imports.enabled=false"
        })
@ActiveProfiles("test")
class LocalClientPairingHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    @Test
    void 回环设备配对后可签名领取且挑战不可重放() throws Exception {
        UUID tenant = UUID.randomUUID(), pairingId = UUID.randomUUID();
        String owner = "pairing-test", token = jwt.createToken(tenant, owner, "测试用户", List.of("agent:run"));
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] nonce = new byte[32];
        new java.security.SecureRandom().nextBytes(nonce);
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        byte[] proof = DevicePairingSignatures.sign(pairingId, nonce, "测试机", LocalClientDevice.Platform.WINDOWS,
                publicKey, pair.getPrivate());
        String root = "http://127.0.0.1:" + port;
        try (var http = HttpClient.newHttpClient()) {
            byte[] packageBody = json.writeValueAsBytes(Map.of("requestId", pairingId, "nonce", Base64.getEncoder().encodeToString(nonce),
                    "deviceName", "测试机", "platform", "WINDOWS", "publicKey", publicKey,
                    "proof", Base64.getEncoder().encodeToString(proof)));
            var begin = send(http, root + "/api/local-client/pairings", token, packageBody);
            assertThat(begin.statusCode()).isEqualTo(200);
            var confirmation = send(http, root + "/api/local-client/pairings/" + pairingId + "/confirm", token, new byte[0]);
            assertThat(confirmation.statusCode()).as("配对确认响应：%s", confirmation.body()).isEqualTo(200);
            UUID device = UUID.fromString(json.readTree(confirmation.body()).path("deviceId").asText());
            var challenge = send(http, root + "/api/local-client/bootstrap/challenges", null,
                    json.writeValueAsBytes(Map.of("deviceId", device)));
            assertThat(challenge.statusCode()).isEqualTo(200);
            var challengeJson = json.readTree(challenge.body());
            UUID challengeId = UUID.fromString(challengeJson.path("challengeId").asText());
            byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String path = "/api/local-client/bootstrap/operations/next";
            var signed = new DeviceRequestProof(challengeId, Base64.getDecoder().decode(challengeJson.path("nonce").asText()),
                    device, tenant, owner, "http://127.0.0.1:8080", "POST", path,
                    MessageDigest.getInstance("SHA-256").digest(body));
            var request = HttpRequest.newBuilder(URI.create(root + path)).header("Content-Type", "application/json")
                    .header("X-CM-Device-Id", device.toString()).header("X-CM-Challenge-Id", challengeId.toString())
                    .header("X-CM-Signature", Base64.getEncoder().encodeToString(DeviceProofSignatures.sign(signed, pair.getPrivate())))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);
            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void 配对与设备管理按操作要求分离agent运行和读取权限() throws Exception {
        UUID tenant = UUID.randomUUID(), pairingId = UUID.randomUUID(), deviceId = UUID.randomUUID();
        String owner = "permission-test";
        String readOnly = jwt.createToken(tenant, owner, "只读用户", List.of("agent:read"));
        String runOnly = jwt.createToken(tenant, owner, "运行用户", List.of("agent:run"));
        String root = "http://127.0.0.1:" + port;
        byte[] pairingBody = json.writeValueAsBytes(Map.of(
                "requestId", pairingId,
                "nonce", Base64.getEncoder().encodeToString(new byte[32]),
                "deviceName", "测试机",
                "platform", "WINDOWS",
                "publicKey", "test-public-key",
                "proof", Base64.getEncoder().encodeToString(new byte[64])));
        try (var http = HttpClient.newHttpClient()) {
            assertThat(send(http, root + "/api/local-client/pairings", readOnly, pairingBody).statusCode())
                    .isEqualTo(403);
            assertThat(send(http, root + "/api/local-client/pairings/" + pairingId + "/confirm",
                    readOnly, new byte[0]).statusCode()).isEqualTo(403);
            assertThat(send(http, root + "/api/local-client/devices/" + deviceId + "/revoke",
                    readOnly, json.writeValueAsBytes(Map.of("expectedVersion", 0))).statusCode()).isEqualTo(403);
            assertThat(get(http, root + "/api/local-client/devices", runOnly).statusCode()).isEqualTo(403);
            assertThat(get(http, root + "/api/local-client/devices", readOnly).statusCode()).isEqualTo(200);
        }
    }

    private static HttpResponse<String> send(HttpClient http, String url, String token, byte[] body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(HttpClient http, String url, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
