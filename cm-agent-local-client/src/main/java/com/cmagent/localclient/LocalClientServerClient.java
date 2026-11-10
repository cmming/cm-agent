package com.cmagent.localclient;

import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.security.DevicePairingSignatures;
import com.cmagent.core.security.DeviceProofSignatures;
import com.cmagent.core.security.DeviceRequestProof;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 本机客户端与 CM Agent Server 的配对、挑战和签名任务协议。
 *
 * <p>配对包包含公钥与持钥签名，浏览器只负责把它交给已登录服务端。配对后的每个任务请求都先
 * 取得短期挑战，再把方法、实际路径、正文摘要、设备和原主体绑定进 Ed25519 签名。签名请求不自动
 * 重试；响应丢失时结果按未知处理，避免重复外部副作用。</p>
 */
final class LocalClientServerClient implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String DEVICE_HEADER = "X-CM-Device-Id";
    private static final String CHALLENGE_HEADER = "X-CM-Challenge-Id";
    private static final String SIGNATURE_HEADER = "X-CM-Signature";
    private final ObjectMapper json;
    private final SecureRandom random;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    LocalClientServerClient(ObjectMapper json, SecureRandom random) {
        this.json = Objects.requireNonNull(json);
        this.random = Objects.requireNonNull(random);
    }

    /**
     * 为当前设备密钥创建一次性配对包。
     *
     * @param identity 已持久化的本机身份
     * @return 可交给浏览器转交服务端的公开配对包
     */
    PairingPackage pairingPackage(DeviceIdentityStore.StoredIdentity identity)
            throws GeneralSecurityException {
        UUID requestId = UUID.randomUUID();
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        var metadata = identity.metadata();
        var platform = LocalClientDevice.Platform.valueOf(metadata.platform());
        byte[] proof = DevicePairingSignatures.sign(requestId, nonce, metadata.deviceName(), platform,
                metadata.publicKey(), identity.keyPair().getPrivate());
        return new PairingPackage(requestId, Base64.getEncoder().encodeToString(nonce),
                metadata.deviceName(), platform, metadata.publicKey(),
                Base64.getEncoder().encodeToString(proof));
    }

    /**
     * 轮询浏览器是否确认配对。
     *
     * @param serverUrl 规范服务端来源
     * @param pairingId 客户端配对请求标识
     * @return 已登记时返回公开状态；浏览器尚未提交配对包时返回空
     */
    Optional<PairingStatus> pairingStatus(URI serverUrl, UUID pairingId) throws IOException, InterruptedException {
        URI endpoint = resolve(serverUrl, "/api/local-client/bootstrap/pairings/" + pairingId);
        HttpResponse<String> response = http.send(request(endpoint).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        // 客户端先显示配对包，用户稍后才会在浏览器登记；此期间 404 是等待状态。
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) throw new IOException("配对状态查询失败");
        return Optional.of(decode(response.body(), PairingStatus.class, "配对状态响应无效"));
    }

    /**
     * 领取一条签名任务。
     *
     * @param identity 已确认配对身份
     * @return 有任务时的解密请求，否则为空
     */
    Optional<Task> nextTask(DeviceIdentityStore.StoredIdentity identity)
            throws IOException, InterruptedException, GeneralSecurityException {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        URI endpoint = resolve(identityServer(identity), "/api/local-client/bootstrap/operations/next");
        HttpResponse<String> response = signedPost(identity, endpoint, body);
        if (response.statusCode() == 204) return Optional.empty();
        if (response.statusCode() == 401) {
            // 撤销也可能发生在签发挑战后；签名无效与挑战过期同样返回 401，不能误断言已撤销。
            throw new ClientFailureException("设备签名未通过、挑战已过期或设备已撤销，请在控制台核对设备和服务状态", null);
        }
        if (response.statusCode() != 200) throw new IOException("本机任务领取失败");
        return Optional.of(decode(response.body(), Task.class, "本机任务响应无效"));
    }

    /**
     * 领取一条已配对设备项目任务；与通用绝对路径任务使用独立端点。
     *
     * @param identity 已确认配对身份
     * @return 有任务时的固定请求，否则为空
     */
    Optional<Task> nextProjectTask(DeviceIdentityStore.StoredIdentity identity)
            throws IOException, InterruptedException, GeneralSecurityException {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        URI endpoint = resolve(identityServer(identity),
                "/api/local-client/bootstrap/project-tasks/next");
        HttpResponse<String> response = signedPost(identity, endpoint, body,
                Map.of("X-CM-Client-Project-Protocol",
                        Integer.toString(com.cmagent.core.domain.ClientProjectBinding.PROTOCOL)));
        if (response.statusCode() == 204) return Optional.empty();
        if (response.statusCode() == 401) {
            throw new ClientFailureException(
                    "设备签名未通过、挑战已过期或设备已撤销，请在控制台核对设备和服务状态", null);
        }
        if (response.statusCode() != 200) throw new IOException("设备项目任务领取失败");
        return Optional.of(decode(response.body(), Task.class, "设备项目任务响应无效"));
    }

    /**
     * 回报一条任务的终态。
     *
     * @param identity 已确认配对身份
     * @param task 本次领取任务
     * @param state 成功、失败或未知
     * @param output 成功 JSON；当前客户端原语不产生其他类型
     * @param error 失败或未知的安全原因
     */
    void reportTask(DeviceIdentityStore.StoredIdentity identity, Task task,
                    String state, JsonNode output, String error)
            throws IOException, InterruptedException, GeneralSecurityException {
        if (state == null || !("SUCCEEDED".equals(state) || "FAILED".equals(state) || "UNKNOWN".equals(state))) {
            throw new IllegalArgumentException("本机任务终态无效");
        }
        byte[] body = json.writeValueAsBytes(new ResultPayload(state, task.version(), output, error));
        URI endpoint = resolve(identityServer(identity),
                "/api/local-client/bootstrap/operations/" + task.operationId() + "/result");
        HttpResponse<String> response;
        try {
            response = signedPost(identity, endpoint, body);
        } catch (DeviceUnavailableException failure) {
            // 结果回报发生在本机副作用之后，设备失效不能证明操作没有执行。
            throw new DeviceUnavailableException(true);
        } catch (IOException failure) {
            throw new ClientFailureException("本机操作已执行，但结果回报失败；副作用可能已发生，请勿自动重试", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ClientFailureException("结果回报被中断；本机操作可能已发生，请勿自动重试", interrupted);
        } catch (GeneralSecurityException failure) {
            throw new ClientFailureException("结果签名失败；本机操作可能已发生，请勿自动重试", failure);
        }
        if (response.statusCode() != 204) throw new ClientFailureException(
                "本机操作已执行，但结果回报未被接受；副作用可能已发生，请勿自动重试", null);
    }

    /**
     * 回报设备项目任务终态。
     *
     * @param identity 已确认配对身份
     * @param task 本次领取任务
     * @param state 成功、失败或未知
     * @param output 成功结构化结果
     * @param error 失败或未知安全原因
     */
    void reportProjectTask(DeviceIdentityStore.StoredIdentity identity, Task task,
            String state, JsonNode output, String error)
            throws IOException, InterruptedException, GeneralSecurityException {
        if (state == null || !("SUCCEEDED".equals(state) || "FAILED".equals(state)
                || "UNKNOWN".equals(state))) {
            throw new IllegalArgumentException("设备项目任务终态无效");
        }
        byte[] body = json.writeValueAsBytes(new ResultPayload(state, task.version(), output, error));
        URI endpoint = resolve(identityServer(identity),
                "/api/local-client/bootstrap/project-tasks/" + task.operationId() + "/result");
        HttpResponse<String> response = signedPost(identity, endpoint, body,
                Map.of("X-CM-Client-Project-Protocol",
                        Integer.toString(com.cmagent.core.domain.ClientProjectBinding.PROTOCOL)));
        if (response.statusCode() != 204) throw new ClientFailureException(
                "设备项目任务结果未被接受，请核对任务状态，勿自动重试", null);
    }

    private HttpResponse<String> signedPost(DeviceIdentityStore.StoredIdentity identity, URI endpoint, byte[] body)
            throws IOException, InterruptedException, GeneralSecurityException {
        return signedPost(identity, endpoint, body, Map.of());
    }

    private HttpResponse<String> signedPost(DeviceIdentityStore.StoredIdentity identity, URI endpoint,
            byte[] body, Map<String, String> extraHeaders)
            throws IOException, InterruptedException, GeneralSecurityException {
        Challenge challenge = challenge(identity, endpoint);
        var metadata = identity.metadata();
        byte[] signature;
        try {
            var proof = new DeviceRequestProof(challenge.challengeId(),
                    Base64.getDecoder().decode(challenge.nonce()), metadata.deviceId(),
                    metadata.tenantId(), metadata.ownerId(), audience(identityServer(identity)),
                    "POST", endpoint.getRawPath(), sha256(body));
            signature = DeviceProofSignatures.sign(proof, identity.keyPair().getPrivate());
        } catch (IllegalArgumentException invalid) {
            throw new GeneralSecurityException("设备挑战格式无效", invalid);
        }
        var builder = request(endpoint)
                .header("Content-Type", "application/json")
                .header(DEVICE_HEADER, metadata.deviceId().toString())
                .header(CHALLENGE_HEADER, challenge.challengeId().toString())
                .header(SIGNATURE_HEADER, Base64.getEncoder().encodeToString(signature))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        extraHeaders.forEach(builder::header);
        HttpRequest request = builder.build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private Challenge challenge(DeviceIdentityStore.StoredIdentity identity, URI endpoint)
            throws IOException, InterruptedException {
        URI challengeEndpoint = resolve(identityServer(identity), "/api/local-client/bootstrap/challenges");
        byte[] body = json.writeValueAsBytes(new ChallengeRequest(identity.metadata().deviceId()));
        HttpRequest request = request(challengeEndpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 429) throw new ChallengeRateLimitedException();
        // 404 同时可能表示设备不存在、已撤销或服务端未开放端点，不能断言具体原因。
        if (response.statusCode() == 404) throw new DeviceUnavailableException(false);
        if (response.statusCode() != 200) throw new IOException("设备挑战获取失败，请检查服务状态和网络连接");
        return decode(response.body(), Challenge.class, "设备挑战响应无效");
    }

    private HttpRequest.Builder request(URI endpoint) {
        return HttpRequest.newBuilder(endpoint).timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json");
    }

    private static URI identityServer(DeviceIdentityStore.StoredIdentity identity) {
        return URI.create(identity.metadata().serverUrl());
    }

    private static URI resolve(URI origin, String path) {
        return URI.create(audience(origin) + path);
    }

    private static String audience(URI origin) {
        int port = origin.getPort();
        return origin.getScheme() + "://" + origin.getHost() + (port == -1 ? "" : ":" + port);
    }

    private <T> T decode(String body, Class<T> type, String message) throws IOException {
        try {
            T value = json.readValue(body, type);
            if (value == null) throw new IOException(message);
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IOException(message, invalid);
        }
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    @Override public void close() {
        http.shutdownNow();
    }

    /**
     * 浏览器提交的配对字段。
     *
     * @param requestId 客户端随机配对标识
     * @param nonce 随机值 Base64 编码
     * @param deviceName 设备名称
     * @param platform 设备平台
     * @param publicKey 公钥 Base64 编码
     * @param proof 配对签名 Base64 编码
     */
    record PairingPackage(UUID requestId, String nonce, String deviceName,
                          LocalClientDevice.Platform platform, String publicKey, String proof) { }

    /** 服务端配对公开状态。 */
    record PairingStatus(UUID pairingId, String state, String deviceName,
                         LocalClientDevice.Platform platform, UUID deviceId,
                         UUID tenantId, String ownerId, String expiresAt, String confirmedAt) { }

    /** 服务端短期挑战。 */
    record Challenge(UUID challengeId, String nonce, String expiresAt) { }

    /** 服务端任务请求。 */
    record Task(UUID operationId, String category, long version, JsonNode request) { }

    private record ChallengeRequest(UUID deviceId) { }
    private record ResultPayload(String state, long expectedVersion, JsonNode output, String error) { }

    /** 服务端无法识别设备或未开放设备协议；调用方必须提示用户核对而不自动重配。 */
    static class ClientFailureException extends IOException {
        ClientFailureException(String message, Throwable cause) { super(message, cause); }
    }

    /** 挑战端点限流发生在任务领取之前；仅此阶段允许调用方有界延后重试。 */
    static final class ChallengeRateLimitedException extends IOException {
        ChallengeRateLimitedException() { super("设备挑战请求过于频繁"); }
    }

    static final class DeviceUnavailableException extends ClientFailureException {
        /** @param reporting 是否发生在本机操作之后的结果回报阶段 */
        DeviceUnavailableException(boolean reporting) {
            super(reporting
                    ? "设备不可用，可能已撤销或服务端未开放；本机操作可能已发生，请在控制台核对结果，勿自动重试"
                    : "设备不存在、已撤销或服务端未开放，请先在控制台核对设备状态及服务配置；确已撤销时再重新配对", null);
        }
    }
}
