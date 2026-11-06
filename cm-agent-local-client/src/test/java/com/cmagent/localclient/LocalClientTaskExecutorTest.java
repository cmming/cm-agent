package com.cmagent.localclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalClientTaskExecutorTest {
    @Test
    void 读取文件工具接受模型适配器产生的数字字符串(@TempDir Path directory) throws Exception {
        var json = new ObjectMapper();
        var executor = new LocalClientTaskExecutor(json);
        var file = directory.resolve("sample.txt");
        java.nio.file.Files.writeString(file, "local-client");

        var list = json.valueToTree(Map.of(
                "action", "list", "path", directory.toString(), "limit", "50"));
        var listResult = executor.execute(
                new LocalClientServerClient.Task(UUID.randomUUID(), "READ_ONLY", 1, list));
        assertThat(listResult.success()).isTrue();
        assertThat(listResult.output().path("entries").isArray()).isTrue();

        var read = json.valueToTree(Map.of(
                "action", "read", "path", file.toString(), "offset", "0", "limit", "20"));
        var readResult = executor.execute(
                new LocalClientServerClient.Task(UUID.randomUUID(), "READ_ONLY", 1, read));
        assertThat(readResult.success()).isTrue();
        assertThat(readResult.output().path("contentBase64").asText()).isEqualTo("bG9jYWwtY2xpZW50");
    }

    @Test
    void 追加文件工具接受模型适配器产生的期望大小字符串(@TempDir Path directory) throws Exception {
        var json = new ObjectMapper();
        var executor = new LocalClientTaskExecutor(json);
        var file = directory.resolve("append.txt");
        java.nio.file.Files.writeString(file, "abc");

        var append = json.valueToTree(Map.of(
                "action", "append",
                "path", file.toString(),
                "expectedSize", "3",
                "contentBase64", "ZGVm"));
        var result = executor.execute(
                new LocalClientServerClient.Task(UUID.randomUUID(), "FILE_CHANGE", 1, append));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("action").asText()).isEqualTo("append");
        assertThat(java.nio.file.Files.readString(file)).isEqualTo("abcdef");
    }

    @Test
    void 空请求体的Get和空文件创建均可执行(@TempDir Path directory) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); });
        server.start();
        try {
            var json = new ObjectMapper();
            var executor = new LocalClientTaskExecutor(json);
            var network = json.valueToTree(Map.of("method", "GET", "url", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "headers", Map.of(), "bodyBase64", "", "timeoutSeconds", 5, "responseLimit", 100));
            var result = executor.execute(new LocalClientServerClient.Task(UUID.randomUUID(), "NETWORK", 1, network));
            assertThat(result.success()).isTrue();
            assertThat(result.output().path("statusCode").asInt()).isEqualTo(204);
            var file = directory.resolve("empty.txt");
            var input = json.valueToTree(Map.of("action", "create", "path", file.toString(), "contentBase64", ""));
            assertThat(executor.execute(new LocalClientServerClient.Task(UUID.randomUUID(), "FILE_CHANGE", 1, input)).success()).isTrue();
            assertThat(java.nio.file.Files.size(file)).isZero();
        } finally {
            server.stop(0);
        }
    }
}
