package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.security.OperationCategory;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 已配对设备项目的固定工具目录。
 *
 * <p>ID 命名空间与 Server 本机项目分离。工具只进入匹配来源的项目 Run，
 * 不能由同名定义、endpoint 或模型参数改变执行设备。</p>
 */
public final class ClientProjectToolCatalog {
    /** 当前固定目录；文件变更只有经过 HIGH 审批的应用工具才产生副作用。 */
    public enum Kind {
        /** 有界项目目录。 */ LIST("client_project_list_files", ToolRiskLevel.LOW,
                OperationCategory.READ_ONLY, "列出已配对设备项目根内目录"),
        /** 有界脱敏文本。 */ READ("client_project_read_file", ToolRiskLevel.LOW,
                OperationCategory.READ_ONLY, "读取已配对设备项目内的有界脱敏文本"),
        /** 文件名或脱敏正文搜索。 */ SEARCH("client_project_search_files", ToolRiskLevel.LOW,
                OperationCategory.READ_ONLY, "在已配对设备项目内执行有界字面搜索"),
        /** 生成零项目副作用的受保护文件变更计划。 */ PROPOSE_CHANGE(
                "client_project_propose_changes", ToolRiskLevel.MEDIUM,
                OperationCategory.READ_ONLY, "在已配对设备项目内形成条件文件变更计划"),
        /** 对精确计划执行一次 HIGH 审批后的条件应用。 */ APPLY_CHANGE(
                "client_project_apply_changes", ToolRiskLevel.HIGH,
                OperationCategory.FILE_CHANGE, "应用已批准且仍匹配对象版本的设备项目变更"),
        /** 从成功操作生成新的条件撤销计划。 */ PROPOSE_UNDO(
                "client_project_propose_undo", ToolRiskLevel.MEDIUM,
                OperationCategory.READ_ONLY, "为已成功的设备项目变更形成新撤销计划"),
        /** 读取原主体的安全操作状态。 */ CHANGE_STATUS(
                "client_project_change_status", ToolRiskLevel.LOW,
                OperationCategory.READ_ONLY, "查询设备项目变更的安全状态"),
        /** 查询客户端本地显式配置的固定工具链。 */ LIST_TOOLCHAINS(
                "client_project_list_toolchains", ToolRiskLevel.LOW,
                OperationCategory.READ_ONLY, "查询已配对设备显式配置的固定工具链，不返回宿主路径"),
        /** 在客户端生成零副作用命令清单。 */ PROPOSE_COMMAND(
                "client_project_propose_command", ToolRiskLevel.MEDIUM,
                OperationCategory.READ_ONLY, "生成可精确审批的固定工具链命令清单"),
        /** 精确 HIGH 审批后单次启动命令。 */ RUN_COMMAND(
                "client_project_run_command", ToolRiskLevel.HIGH,
                OperationCategory.COMMAND, "单次启动已批准的固定工具链命令"),
        /** 查询设备进程和脱敏输出快照。 */ COMMAND_STATUS(
                "client_project_command_status", ToolRiskLevel.LOW,
                OperationCategory.READ_ONLY, "查询固定工具链命令的权威状态和脱敏输出"),
        /** 请求停止指定命令并继续对账。 */ CANCEL_COMMAND(
                "client_project_cancel_command", ToolRiskLevel.MEDIUM,
                OperationCategory.COMMAND, "请求停止指定固定工具链命令，不保证立即退出");

        private final String toolName;
        private final ToolRiskLevel risk;
        private final OperationCategory category;
        private final String description;

        Kind(String toolName, ToolRiskLevel risk, OperationCategory category, String description) {
            this.toolName = toolName;
            this.risk = risk;
            this.category = category;
            this.description = description;
        }

        public String toolName() { return toolName; }
        public ToolRiskLevel risk() { return risk; }
        public OperationCategory category() { return category; }
    }

    private final ToolDefinitionRepository tools;
    private final PermissionEvaluator permissions;
    private final TransactionTemplate transaction;
    private final AuditAppender audit;
    private final ObjectMapper json;

    public ClientProjectToolCatalog(ToolDefinitionRepository tools, PermissionEvaluator permissions,
            TransactionTemplate transaction, AuditAppender audit, ObjectMapper json) {
        this.tools = tools;
        this.permissions = permissions;
        this.transaction = transaction;
        this.audit = audit;
        this.json = json;
    }

    public static UUID id(UUID tenantId, Kind kind) {
        return UUID.nameUUIDFromBytes(("cm-agent/client-project/v1/" + tenantId + "/" + kind.name())
                .getBytes(StandardCharsets.UTF_8));
    }

    static Optional<Kind> kind(UUID tenantId, UUID toolId) {
        return Arrays.stream(Kind.values()).filter(value -> id(tenantId, value).equals(toolId)).findFirst();
    }

    static boolean managed(UUID tenantId, UUID toolId) {
        return kind(tenantId, toolId).isPresent();
    }

    public synchronized List<ToolDefinition> install(PrincipalRef principal) {
        authorize(principal, "project:manage", "CLIENT_PROJECT_TOOL_PERMISSION");
        authorize(principal, "tool:grant", "CLIENT_PROJECT_TOOL_PERMISSION");
        return transaction.execute(status -> {
            List<ToolDefinition> result = new ArrayList<>();
            for (Kind kind : Kind.values()) {
                ToolDefinition expected = definition(principal.tenantId(), kind);
                var stored = tools.findByTenantAndId(principal.tenantId(), expected.id()).orElse(null);
                if (stored == null) stored = tools.save(expected);
                if (!matches(stored, expected)) {
                    throw new IllegalStateException("已配对设备项目工具定义已变化");
                }
                result.add(stored);
            }
            audit.appendControlled(principal.tenantId(), principal.principalId(),
                    "CLIENT_PROJECT_TOOLS_INSTALL", "CLIENT_PROJECT_TOOLS", "-",
                    "SUCCEEDED", "仅登记固定项目工具目录，未授予Agent");
            return List.copyOf(result);
        });
    }

    List<ToolDefinition> select(UUID tenantId, List<ToolDefinition> authorized) {
        return authorized.stream().filter(tool -> kind(tenantId, tool.id())
                .map(value -> matches(tool, definition(tenantId, value))).orElse(false)).toList();
    }

    boolean valid(ToolDefinition tool) {
        return kind(tool.tenantId(), tool.id())
                .map(value -> matches(tool, definition(tool.tenantId(), value))).orElse(false);
    }

    private void authorize(PrincipalRef principal, String permission, String action) {
        if (!permissions.check(principal, permission).allowed()) {
            String errorId = UUID.randomUUID().toString();
            audit.appendControlled(principal.tenantId(), principal.principalId(), action,
                    "CLIENT_PROJECT_TOOLS", "-", "DENIED",
                    "errorCode=CLIENT_PROJECT_FORBIDDEN errorId=" + errorId);
            throw new LocalProjectAccessException("CLIENT_PROJECT_FORBIDDEN", errorId, 403,
                    "当前账号缺少设备项目工具授权权限");
        }
    }

    private ToolDefinition definition(UUID tenantId, Kind kind) {
        return new ToolDefinition(id(tenantId, kind), tenantId, kind.toolName, kind.description,
                ToolType.LOCAL, schema(kind), kind.risk, true, "client-project:" + kind.name(),
                "cm-agent-client-project", "cm-agent-client-project");
    }

    private static boolean matches(ToolDefinition actual, ToolDefinition expected) {
        return actual.id().equals(expected.id()) && actual.tenantId().equals(expected.tenantId())
                && actual.name().equals(expected.name()) && actual.type() == ToolType.LOCAL
                && actual.enabled() && actual.riskLevel() == expected.riskLevel()
                && java.util.Objects.equals(actual.endpoint(), expected.endpoint())
                && actual.inputSchema().equals(expected.inputSchema())
                && actual.description().equals(expected.description());
    }

    private String schema(Kind kind) {
        Map<String, Object> fields = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        switch (kind) {
            case LIST -> {
                fields.put("path", text());
                fields.put("cursor", Map.of("type", List.of("string", "null"), "maxLength", 1024));
                fields.put("limit", integer(1, 200));
            }
            case READ -> {
                fields.put("path", text());
                fields.put("offset", integer(0, 2_097_152));
                fields.put("maxBytes", integer(4, 65_536));
                required.add("path");
            }
            case SEARCH -> {
                fields.put("query", text());
                fields.put("content", Map.of("type", "boolean"));
                required.add("query");
            }
            case PROPOSE_CHANGE -> {
                fields.put("changes", changeArray());
                required.add("changes");
            }
            case APPLY_CHANGE -> {
                fields.put("operationId", text());
                fields.put("version", integer(1, Integer.MAX_VALUE));
                fields.put("digest", Map.of("type", "string",
                        "pattern", "^[0-9a-f]{64}$"));
                required.addAll(List.of("operationId", "version", "digest"));
            }
            case PROPOSE_UNDO, CHANGE_STATUS -> {
                fields.put("operationId", text());
                required.add("operationId");
            }
            case LIST_TOOLCHAINS -> { }
            case PROPOSE_COMMAND -> {
                fields.put("toolchainId", text());
                fields.put("arguments", Map.of("type", "array", "maxItems", 127,
                        "items", text()));
                fields.put("relativeDirectory", text());
                fields.put("environment", Map.of("type", "object",
                        "additionalProperties", text()));
                fields.put("timeoutSeconds", integer(1, 1800));
                fields.put("maxOutputBytes", integer(1, 524_288));
                required.addAll(List.of("toolchainId", "arguments", "relativeDirectory",
                        "environment", "timeoutSeconds", "maxOutputBytes"));
            }
            case RUN_COMMAND -> {
                fields.put("operationId", text());
                fields.put("version", integer(1, Integer.MAX_VALUE));
                fields.put("digest", Map.of("type", "string",
                        "pattern", "^[0-9a-f]{64}$"));
                required.addAll(List.of("operationId", "version", "digest"));
            }
            case COMMAND_STATUS, CANCEL_COMMAND -> {
                fields.put("operationId", text());
                required.add("operationId");
            }
        }
        try {
            return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(Map.of("type", "object", "additionalProperties", false,
                            "properties", fields, "required", required));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("设备项目工具输入模式无法构造", failure);
        }
    }

    private static Map<String, Object> text() {
        return Map.of("type", "string", "maxLength", 1024);
    }

    private static Map<String, Object> integer(int minimum, int maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }

    private static Map<String, Object> changeArray() {
        Map<String, Object> change = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of(
                        "kind", Map.of("type", "string", "enum",
                                List.of("CREATE_FILE", "REPLACE_FILE", "DELETE_FILE",
                                        "MOVE_FILE", "CREATE_DIRECTORY",
                                        "DELETE_EMPTY_DIRECTORY")),
                        "path", text(),
                        "target", text(),
                        "content", Map.of("type", "string", "maxLength", 393216),
                        "expectedSha256", Map.of("type", "string",
                                "pattern", "^[0-9a-f]{64}$")),
                "required", List.of("kind", "path"));
        return Map.of("type", "array", "minItems", 1, "maxItems", 20, "items", change);
    }
}
