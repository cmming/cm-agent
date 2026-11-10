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
import java.util.UUID;

/** 在签名任务已通过服务端授权后执行本机文件、网络和命令原语。 */
final class LocalClientTaskExecutor implements AutoCloseable {
    private final ObjectMapper json;
    private final LocalFileOperations files = new LocalFileOperations();
    private final ClientProjectRootStore projectRoots;
    private final ClientDirectoryPicker directoryPicker;
    private final ClientProjectFileOperations projectFiles;
    private final ClientProjectChangeOperations projectChanges;
    private final ClientProjectCommandOperations projectCommands;

    LocalClientTaskExecutor(ObjectMapper json) {
        this(json, ClientProjectRootStore.forCurrentUser(Objects.requireNonNull(json)),
                new TimedClientDirectoryPicker(new WindowsInteractiveDirectoryPicker()));
    }

    LocalClientTaskExecutor(ObjectMapper json, ClientProjectRootStore projectRoots,
            ClientDirectoryPicker directoryPicker) {
        this.json = Objects.requireNonNull(json);
        this.projectRoots = Objects.requireNonNull(projectRoots);
        this.directoryPicker = Objects.requireNonNull(directoryPicker);
        this.projectFiles = new ClientProjectFileOperations(projectRoots, json);
        this.projectChanges = new ClientProjectChangeOperations(projectRoots, json);
        this.projectCommands = new ClientProjectCommandOperations(projectRoots, json);
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

    /**
     * 执行普通或专用项目任务；项目选择必须匹配当前已配对设备。
     *
     * @param identity 当前签名设备身份
     * @param task 已签名领取的任务
     * @return 可签名回报的结果
     */
    Outcome execute(DeviceIdentityStore.StoredIdentity identity, LocalClientServerClient.Task task) {
        Objects.requireNonNull(identity, "设备身份不能为空");
        if (task.category() == null || !task.category().startsWith("PROJECT_")) {
            return execute(task);
        }
        if (task.request() == null || !task.request().isObject()) {
            return Outcome.failed("设备项目任务参数无效", json.createObjectNode());
        }
        try {
            return switch (task.category()) {
                case "PROJECT_SELECT_ROOT" -> selectRoot(identity, task);
                case "PROJECT_READ_ONLY" -> projectReadOnly(identity, task);
                case "PROJECT_CHANGE_PLAN" -> projectChangePlan(identity, task);
                case "PROJECT_CHANGE_APPLY" -> projectChangeApply(identity, task);
                case "PROJECT_LIST_TOOLCHAINS" -> projectToolchains(identity, task.request());
                case "PROJECT_COMMAND_PLAN" -> projectCommandPlan(identity, task.request());
                case "PROJECT_COMMAND_RUN" -> projectCommandRun(identity, task.request());
                case "PROJECT_COMMAND_STATUS" -> projectCommandStatus(identity, task.request());
                case "PROJECT_COMMAND_STOP" -> projectCommandStop(identity, task.request());
                default -> Outcome.failed("设备项目任务类别无效", json.createObjectNode());
            };
        } catch (IOException | RuntimeException failure) {
            if ("PROJECT_SELECT_ROOT".equals(task.category())) {
                String errorId = UUID.randomUUID().toString();
                boolean conflict = failure instanceof ClientProjectRootStore.RootConflictException;
                boolean expired = failure instanceof SelectionExpiredException;
                String errorCode = conflict ? "CLIENT_PROJECT_ROOT_CONFLICT" : expired
                        ? "CLIENT_PROJECT_SELECTION_EXPIRED" : "CLIENT_PROJECT_SELECTION_FAILED";
                // 仅使用已配对身份和服务端任务标识；异常原文可能含本机路径或凭据，不能记录。
                System.err.printf("%s errorId=%s operation=client_project_select errorCode=%s tenantId=%s resourceType=CLIENT_PROJECT_TASK resourceId=%s%n",
                        conflict || expired ? "WARN" : "ERROR", errorId, errorCode, identity.metadata().tenantId(), task.taskId());
                if (!conflict && !expired) {
                    var sanitized = new IllegalStateException("本机目录选择失败，异常内容已脱敏");
                    sanitized.setStackTrace(failure.getStackTrace());
                    sanitized.printStackTrace(System.err);
                }
                String reason = conflict ? "所选目录与已登记项目相同或互相包含，请使用原项目"
                        : expired ? "目录选择请求已过期，请在控制台核对原请求"
                        : "本机目录选择失败，请核对窗口、目录权限和客户端状态";
                return Outcome.failed(reason + "（错误码：" + errorCode + "，错误编号：" + errorId + "）",
                        json.createObjectNode());
            }
            return Outcome.failed("设备项目任务失败，请核对目录和设备状态", json.createObjectNode());
        }
    }

    private Outcome selectRoot(DeviceIdentityStore.StoredIdentity identity,
            LocalClientServerClient.Task task) throws IOException {
        UUID registrationId = task.taskId();
        java.time.Instant deadline;
        try {
            deadline = java.time.Instant.parse(task.request().path("expiresAt").asText());
        } catch (java.time.DateTimeException invalid) {
            throw new IOException("选择任务期限无效");
        }
        if (!deadline.isAfter(java.time.Instant.now())) throw new SelectionExpiredException();
        if (registrationId == null || deadline.isAfter(java.time.Instant.now().plusSeconds(300))) {
            throw new IOException("选择任务标识或期限无效");
        }
        String origin;
        try {
            var server = java.net.URI.create(identity.metadata().serverUrl());
            // 仅展示配对来源的协议、主机和端口，不回显 URI 中可能存在的用户信息。
            origin = new java.net.URI(server.getScheme(), null, server.getHost(), server.getPort(),
                    null, null, null).toASCIIString();
        } catch (java.net.URISyntaxException | IllegalArgumentException invalid) {
            throw new IOException("配对服务端来源无效");
        }
        String deviceName = identity.metadata().deviceName().replaceAll("[\\p{Cc}\\p{Cf}]", "?");
        var selected = directoryPicker.choose(new ClientDirectoryPicker.Request(
                deviceName, origin, registrationId, deadline));
        if (selected.isEmpty()) {
            return Outcome.failed("用户取消了项目文件夹选择", json.createObjectNode());
        }
        // 迟到结果不得产生本地根绑定；任务编号必须是服务端派发的一次性标识。
        if (!deadline.isAfter(java.time.Instant.now())) throw new SelectionExpiredException();
        var binding = projectRoots.register(identity, registrationId, selected.orElseThrow());
        ObjectNode output = json.createObjectNode();
        output.put("rootId", binding.rootId().toString());
        output.put("rootIdentity", binding.identity());
        output.put("displayName", binding.displayName());
        return Outcome.succeeded(output);
    }

    /** 已过期的选择请求是受控拒绝，不再登记根或再次弹窗。 */
    private static final class SelectionExpiredException extends IOException { }

    private Outcome projectReadOnly(DeviceIdentityStore.StoredIdentity identity,
            LocalClientServerClient.Task task) throws IOException {
        String kind = task.request().path("kind").asText("");
        ObjectNode output = switch (kind) {
            case "LIST" -> projectFiles.list(identity, task.request());
            case "READ" -> projectFiles.read(identity, task.request());
            case "SEARCH" -> projectFiles.search(identity, task.request());
            default -> throw new IOException("设备项目只读任务种类无效");
        };
        return Outcome.succeeded(output);
    }

    private Outcome projectChangePlan(DeviceIdentityStore.StoredIdentity identity,
            LocalClientServerClient.Task task) {
        try {
            return Outcome.succeeded(projectChanges.plan(identity, task.request()));
        } catch (IOException | RuntimeException failure) {
            return Outcome.failed("设备项目变更计划未通过本地核对", json.createObjectNode());
        }
    }

    private Outcome projectChangeApply(DeviceIdentityStore.StoredIdentity identity,
            LocalClientServerClient.Task task) {
        try {
            return Outcome.succeeded(projectChanges.apply(identity, task.request()));
        } catch (ClientProjectChangeOperations.PossibleSideEffectException failure) {
            return Outcome.unknown("设备项目变更结果未知，可能已有部分文件发生变化");
        } catch (IOException | RuntimeException failure) {
            return Outcome.failed("设备项目变更在写入前被拒绝", json.createObjectNode()
                    .put("possibleSideEffects", false));
        }
    }

    private Outcome projectCommandRun(DeviceIdentityStore.StoredIdentity identity,
            JsonNode request) {
        try {
            return Outcome.succeeded(projectCommands.start(identity, request));
        } catch (ClientProjectCommandOperations.PossibleSideEffectException failure) {
            return Outcome.unknown("命令可能已经启动但无法确认，请核对设备和本地回执");
        } catch (IOException | RuntimeException failure) {
            return Outcome.failed("固定工具链命令在启动前被拒绝", json.createObjectNode()
                    .put("possibleSideEffects", false));
        }
    }

    private Outcome projectToolchains(DeviceIdentityStore.StoredIdentity identity,
            JsonNode request) {
        try {
            return Outcome.succeeded(projectCommands.list(identity, request));
        } catch (IOException | RuntimeException failure) {
            return Outcome.failed("客户端固定工具链配置不可用", json.createObjectNode());
        }
    }

    private Outcome projectCommandPlan(DeviceIdentityStore.StoredIdentity identity,
            JsonNode request) {
        try {
            return Outcome.succeeded(projectCommands.plan(identity, request));
        } catch (IOException | RuntimeException failure) {
            return Outcome.failed("设备项目命令计划未通过本地核对", json.createObjectNode());
        }
    }

    private Outcome projectCommandStatus(DeviceIdentityStore.StoredIdentity identity,
            JsonNode request) {
        try {
            return Outcome.succeeded(projectCommands.status(identity, request));
        } catch (IOException | RuntimeException failure) {
            return Outcome.failed("设备项目命令状态不可用", json.createObjectNode());
        }
    }

    private Outcome projectCommandStop(DeviceIdentityStore.StoredIdentity identity,
            JsonNode request) {
        try {
            return Outcome.succeeded(projectCommands.stop(identity, request));
        } catch (IOException | RuntimeException failure) {
            return Outcome.failed("设备项目命令停止请求未送达", json.createObjectNode());
        }
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

    /** 客户端退出时先请求停止所有项目命令，并等待有界进程树收口。 */
    @Override public void close() {
        projectCommands.close();
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
