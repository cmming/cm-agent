package com.cmagent.localclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 在签名任务已通过服务端授权后执行本机文件、网络和命令原语。 */
final class LocalClientTaskExecutor {
    private final ObjectMapper json;
    private final LocalFileOperations files = new LocalFileOperations();

    LocalClientTaskExecutor(ObjectMapper json) {
        this.json = Objects.requireNonNull(json);
    }

    /**
     * 执行一条类别和输入均由服务端固定任务描述器给出的操作。
     *
     * @param task 已签名取得的任务
     * @return 可回报的终态、安全摘要和结果 JSON
     */
    Outcome execute(LocalClientServerClient.Task task) {
        Objects.requireNonNull(task, "本机任务不能为空");
        if (task.request() == null || !task.request().isObject()) {
            return Outcome.failed("本机任务参数无效", json.createObjectNode());
        }
        return switch (task.category()) {
            case "READ_ONLY" -> readOrList(task.request());
            case "FILE_CHANGE" -> change(task.request());
            case "NETWORK" -> network(task.request());
            case "COMMAND" -> command(task.request());
            default -> Outcome.failed("本机任务类别无效", json.createObjectNode());
        };
    }

    private Outcome readOrList(JsonNode input) {
        try {
            String action = text(input, "action");
            Path path = Path.of(text(input, "path"));
            ObjectNode output = json.createObjectNode();
            if ("list".equals(action)) {
                String after = input.path("after").isNull() || input.path("after").isMissingNode()
                        ? null : input.path("after").asText();
                var page = files.list(path, after, integer(input, "limit", 1, 500));
                ArrayNode entries = output.putArray("entries");
                page.entries().forEach(entry -> entries.addObject()
                        .put("name", entry.name()).put("type", entry.type().name()));
                if (page.nextName() == null) output.putNull("nextName");
                else output.put("nextName", page.nextName());
                output.put("incomplete", page.incomplete());
            } else if ("read".equals(action)) {
                long offset = longValue(input, "offset", 0, Long.MAX_VALUE);
                var content = files.read(path, offset,
                        integer(input, "limit", 1, LocalFileOperations.MAX_BYTES));
                output.put("contentBase64", Base64.getEncoder().encodeToString(content.bytes()));
                output.put("truncated", content.truncated());
            } else {
                return Outcome.failed("文件读取动作无效", output);
            }
            return Outcome.succeeded(output);
        } catch (IOException | RuntimeException failure) {
            return Outcome.failed("本机文件读取失败", json.createObjectNode());
        }
    }

    private Outcome change(JsonNode input) {
        ObjectNode output = json.createObjectNode();
        try {
            String action = text(input, "action");
            Path path = Path.of(text(input, "path"));
            switch (action) {
                case "create" -> files.create(path, decoded(input, "contentBase64"));
                case "replace" -> files.replace(path, decoded(input, "contentBase64"));
                case "append" -> files.append(path,
                        longValue(input, "expectedSize", 0, Long.MAX_VALUE),
                        decoded(input, "contentBase64"));
                case "delete" -> files.delete(path);
                default -> {
                    return Outcome.failed("文件变更动作无效", output);
                }
            }
            output.put("action", action);
            return Outcome.succeeded(output);
        } catch (IOException failure) {
            // 写、追加或删除可能在异常前已经产生部分副作用。
            return Outcome.unknown("本机文件操作结果未知");
        } catch (RuntimeException failure) {
            return Outcome.failed("本机文件操作参数无效", output);
        }
    }

    private Outcome network(JsonNode input) {
        try (var http = new LocalHttpOperations()) {
            Map<String, String> headers = new LinkedHashMap<>();
            JsonNode source = input.path("headers");
            if (!source.isObject()) return Outcome.failed("网络请求头无效", json.createObjectNode());
            source.fields().forEachRemaining(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
            byte[] body = decoded(input, "bodyBase64");
            var result = http.request(URI.create(text(input, "url")), text(input, "method"), headers, body,
                    Duration.ofSeconds(integer(input, "timeoutSeconds", 1, 300)),
                    integer(input, "responseLimit", 1, LocalHttpOperations.MAX_BYTES));
            ObjectNode output = json.createObjectNode();
            output.put("statusCode", result.statusCode());
            if (result.location() == null) output.putNull("location");
            else output.put("location", result.location());
            output.put("bodyBase64", Base64.getEncoder().encodeToString(result.body()));
            output.put("truncated", result.truncated());
            return Outcome.succeeded(output);
        } catch (IOException failure) {
            return Outcome.unknown("本机网络请求结果未知");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.unknown("本机网络请求被中断，结果未知");
        } catch (RuntimeException failure) {
            return Outcome.failed("本机网络请求参数无效", json.createObjectNode());
        }
    }

    private Outcome command(JsonNode input) {
        LocalCommandOperations.CommandHandle handle = null;
        try {
            JsonNode argumentsNode = input.path("arguments");
            if (!argumentsNode.isArray() || argumentsNode.isEmpty()) {
                return Outcome.failed("命令参数无效", json.createObjectNode());
            }
            java.util.List<String> arguments = new java.util.ArrayList<>();
            argumentsNode.forEach(value -> arguments.add(value.asText()));
            handle = new LocalCommandOperations().start(arguments,
                    Path.of(text(input, "workingDirectory")),
                    integer(input, "outputLimit", 1, LocalCommandOperations.MAX_OUTPUT_BYTES));
            Duration timeout = Duration.ofSeconds(integer(input, "timeoutSeconds", 1, 1800));
            var status = handle.await(timeout);
            ObjectNode output = json.createObjectNode();
            output.put("exited", status.exited());
            if (status.exitCode() == null) output.putNull("exitCode");
            else output.put("exitCode", status.exitCode());
            output.put("stopRequested", status.stopRequested());
            output.put("outputBase64", Base64.getEncoder().encodeToString(status.output()));
            output.put("truncated", status.truncated());
            output.put("outputComplete", status.outputComplete());
            if (!status.exited()) {
                handle.requestStop();
                return Outcome.unknown("命令等待超时，已尽力停止但派生进程和副作用可能仍存在");
            }
            return status.exitCode() == 0 ? Outcome.succeeded(output)
                    : new Outcome("FAILED", false, output, "命令以非零状态退出");
        } catch (IOException failure) {
            return Outcome.unknown("本机命令启动或读取结果未知");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.unknown("本机命令等待被中断，结果未知");
        } catch (RuntimeException failure) {
            return Outcome.failed("本机命令参数无效", json.createObjectNode());
        } finally {
            if (handle != null) handle.close();
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("字段无效");
        }
        return value.textValue();
    }

    private static int integer(JsonNode node, String name, int minimum, int maximum) {
        JsonNode value = node.get(name);
        if (value == null) throw new IllegalArgumentException("整数无效");
        long parsed;
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            parsed = value.longValue();
        } else if (value.isTextual()) {
            parsed = nonNegativeLong(value.textValue());
        } else {
            throw new IllegalArgumentException("整数无效");
        }
        if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException("整数越界");
        return (int) parsed;
    }

    private static long longValue(JsonNode node, String name, long minimum, long maximum) {
        JsonNode value = node.get(name);
        if (value == null) throw new IllegalArgumentException("长整数无效");
        long result;
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            result = value.longValue();
        } else if (value.isTextual()) {
            result = nonNegativeLong(value.textValue());
        } else {
            throw new IllegalArgumentException("长整数无效");
        }
        if (result < minimum || result > maximum) throw new IllegalArgumentException("长整数越界");
        return result;
    }

    /** AgentScope 的部分模型适配器会把 JSON 数字序列化成十进制字符串；这里仅接受无符号十进制文本。 */
    private static long nonNegativeLong(String value) {
        if (value == null || value.isBlank() || !value.matches("[0-9]+")) {
            throw new IllegalArgumentException("整数文本无效");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("整数越界");
        }
    }

    private static byte[] decoded(JsonNode node, String name) {
        JsonNode encoded = node.get(name);
        if (encoded == null || !encoded.isTextual()) throw new IllegalArgumentException("载荷编码无效");
        // 空 Base64 对应零字节正文；GET 请求和创建空文件均需要保留这一合法输入。
        byte[] value = Base64.getDecoder().decode(encoded.textValue());
        if (value.length > LocalFileOperations.MAX_BYTES) {
            throw new IllegalArgumentException("载荷过大");
        }
        return value;
    }

    /**
     * 任务终态和回报内容。
     *
     * @param state SUCCEEDED、FAILED 或 UNKNOWN
     * @param success 是否确定成功
     * @param output 成功或失败的结构化结果
     * @param error 失败或未知的安全原因
     */
    record Outcome(String state, boolean success, JsonNode output, String error) {
        static Outcome succeeded(JsonNode output) {
            return new Outcome("SUCCEEDED", true, output, "");
        }
        static Outcome failed(String error, JsonNode output) {
            return new Outcome("FAILED", false, output, error);
        }
        static Outcome unknown(String error) {
            return new Outcome("UNKNOWN", false, null, error);
        }
    }
}
