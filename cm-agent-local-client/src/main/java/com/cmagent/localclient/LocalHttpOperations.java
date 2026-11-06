package com.cmagent.localclient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 在客户端所在电脑执行的有界 HTTP 调用；当前尚未连接服务端或模型。
 *
 * <p>调用者必须先完成设备归属、当前运行、工具权限和具体操作的授权。本类使用客户端的网络
 * 环境，不修改 Server 端动态 HTTP 工具的网络策略，也不把网络地址或响应写入日志。</p>
 */
final class LocalHttpOperations implements AutoCloseable {
    /** 单次请求和响应正文的最大字节数。 */
    static final int MAX_BYTES = 4 * 1024 * 1024;
    /** 由本对象持有；关闭时主动取消尚未完成的请求。 */
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // 重定向可能改变目标及副作用，交给上层重新授权，不隐式携带请求头继续访问。
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * 执行一条用户授权的 HTTP 或 HTTPS 请求。
     *
     * <p>超时、取消或连接中断不表示远端没有处理请求；调用方必须将外部副作用视为可能已发生，
     * 不能自动重放。响应正文达到上限时停止接收并标注截断。</p>
     *
     * @param uri 已经授权的本机网络目标
     * @param method HTTP 方法
     * @param headers 请求头；调用方负责保护其中的敏感值
     * @param body 本次请求正文，不能超过固定上限
     * @param timeout 整个响应等待时间，必须为正且不超过五分钟
     * @param responseLimit 本次最多接收的响应字节数
     * @return 状态、重定向位置和有界响应内容
     * @throws IOException 通信失败或超时；远端操作结果可能未知
     * @throws InterruptedException 等待期间被中断，远端操作结果可能未知
     */
    HttpResult request(URI uri, String method, Map<String, String> headers, byte[] body,
                       Duration timeout, int responseLimit) throws IOException, InterruptedException {
        Objects.requireNonNull(uri, "网络目标不能为空");
        Objects.requireNonNull(method, "请求方法不能为空");
        Objects.requireNonNull(headers, "请求头不能为空");
        Objects.requireNonNull(body, "请求正文不能为空");
        Objects.requireNonNull(timeout, "请求超时不能为空");
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("仅支持有效的 HTTP 或 HTTPS 目标");
        }
        if (body.length > MAX_BYTES || responseLimit < 1 || responseLimit > MAX_BYTES
                || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("网络请求的大小或时间上限无效");
        }
        var builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        CompletableFuture<HttpResponse<LimitedBody>> pending = client.sendAsync(
                builder.build(), ignored -> new LimitedSubscriber(responseLimit));
        try {
            HttpResponse<LimitedBody> response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return new HttpResult(response.statusCode(), response.headers().firstValue("Location").orElse(null),
                    response.body().bytes(), response.body().truncated());
        } catch (TimeoutException timeoutFailure) {
            pending.cancel(true);
            throw new IOException("本机网络请求超时，远端处理结果可能未知", timeoutFailure);
        } catch (ExecutionException failure) {
            throw new IOException("本机网络请求未完成，远端处理结果可能未知", failure.getCause());
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            throw interrupted;
        }
    }

    /** 停止由当前实例创建的未完成请求；停止不撤回已经发送的远端请求。 */
    @Override
    public void close() {
        client.shutdownNow();
    }

    /**
     * 一次 HTTP 响应。
     *
     * @param statusCode 远端返回的 HTTP 状态码
     * @param location 未自动跟随的重定向位置；不存在时为 {@code null}
     * @param body 有界响应正文，构造和访问时均复制
     * @param truncated 为 {@code true} 时表示响应还有未接收内容
     */
    record HttpResult(int statusCode, String location, byte[] body, boolean truncated) {
        HttpResult {
            body = Objects.requireNonNull(body, "响应正文不能为空").clone();
        }
        @Override public byte[] body() { return body.clone(); }
    }

    /**
     * 限制响应读取结果。
     *
     * @param bytes 已接收的内容
     * @param truncated 是否因达到预算提前停止接收
     */
    private record LimitedBody(byte[] bytes, boolean truncated) { }

    /** 使用响应流订阅而非无界聚合，超额时取消订阅并保留明确截断状态。 */
    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<LimitedBody> {
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<LimitedBody> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean finished;

        private LimitedSubscriber(int limit) { this.limit = limit; }
        @Override public CompletionStage<LimitedBody> getBody() { return result; }

        @Override public synchronized void onSubscribe(Flow.Subscription next) {
            if (subscription != null || finished) {
                next.cancel();
                return;
            }
            subscription = next;
            next.request(1);
        }

        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (finished) return;
            for (ByteBuffer buffer : buffers) {
                int accepted = Math.min(buffer.remaining(), limit - output.size());
                byte[] bytes = new byte[accepted];
                buffer.get(bytes);
                output.writeBytes(bytes);
                if (buffer.hasRemaining()) {
                    finished = true;
                    subscription.cancel();
                    result.complete(new LimitedBody(output.toByteArray(), true));
                    return;
                }
            }
            subscription.request(1);
        }

        @Override public synchronized void onError(Throwable failure) {
            if (!finished) {
                finished = true;
                result.completeExceptionally(failure);
            }
        }

        @Override public synchronized void onComplete() {
            if (!finished) {
                finished = true;
                result.complete(new LimitedBody(output.toByteArray(), false));
            }
        }
    }
}
