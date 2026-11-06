package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.server.audit.AuditAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 固定项目工具目录；登记定义不自动授予Agent，普通调试/MCP不能通过本目录访问本机根。 */
public final class LocalProjectToolCatalog {
    /** 受信固定工具语义，名称可展示但不能用名称前缀推断安全类型。 */
    public enum Kind {
        /** 有界目录元数据。 */ LIST("project_list_files", ToolRiskLevel.LOW),
        /** 显式有界脱敏文本。 */ READ("project_read_file", ToolRiskLevel.LOW),
        /** 有界脱敏文本或路径搜索。 */ SEARCH("project_search_files", ToolRiskLevel.LOW),
        /** 查询部署明确允许的命令/构建工具链标识，不返回宿主路径或环境值。 */ TOOLCHAINS("project_list_toolchains",ToolRiskLevel.LOW),
        /** 零项目副作用的受保护变更提案。 */ PROPOSE_CHANGE("project_propose_changes", ToolRiskLevel.MEDIUM),
        /** 精确批准后单次应用变更。 */ APPLY_CHANGE("project_apply_changes", ToolRiskLevel.HIGH),
        /** 原操作的条件撤销提案，仍需新的HIGH批准。 */ PROPOSE_UNDO("project_propose_undo", ToolRiskLevel.MEDIUM),
        /** 受信工具链命令提案，尚未执行。 */ PROPOSE_COMMAND("project_propose_command", ToolRiskLevel.MEDIUM),
        /** 精确批准后单次启动命令或构建。 */ RUN_COMMAND("project_run_command", ToolRiskLevel.HIGH),
        /** 本实例脱敏输出及持久权威状态。 */ COMMAND_STATUS("project_command_status", ToolRiskLevel.LOW),
        /** 精确停止本次Job，停止请求不等于进程已经退出。 */ CANCEL_COMMAND("project_cancel_command", ToolRiskLevel.MEDIUM);
        /** 固定协议工具名。 */ private final String name;
        /** 最低风险，持久定义被调低时仍拒绝副作用。 */ private final ToolRiskLevel risk;
        Kind(String name, ToolRiskLevel risk) { this.name = name; this.risk = risk; }
        public String toolName() { return name; }
    }

    /** 当前租户的工具定义，不能由模型自报。 */ private final ToolDefinitionRepository tools;
    /** 项目管理及既有工具治理登记权限，不能以不存在的权限码阻断正式登录主体。 */ private final LocalProjectAccessService access;
    /** 元数据登记与审计共享短事务。 */ private final TransactionTemplate transaction;
    /** 严格审计不包含工具参数。 */ private final AuditAppender audit;
    /** 仅序列化固定Schema，不执行客户端Schema。 */ private final ObjectMapper json;

    LocalProjectToolCatalog(ToolDefinitionRepository tools, LocalProjectAccessService access, TransactionTemplate transaction, AuditAppender audit, ObjectMapper json) {
        this.tools = tools; this.access = access; this.transaction = transaction; this.audit = audit; this.json = json;
    }

    /** @param tenant 可信租户 @param kind 固定工具类型 @return 与租户及协议版本绑定的精确ID，不因名称编辑变化 */
    public static UUID id(UUID tenant, Kind kind) { return UUID.nameUUIDFromBytes(("cm-agent/local-project/v1/" + tenant + "/" + kind.name()).getBytes(StandardCharsets.UTF_8)); }
    static Optional<Kind> kind(UUID tenant, UUID toolId) { return Arrays.stream(Kind.values()).filter(kind -> id(tenant, kind).equals(toolId)).findFirst(); }
    static boolean managed(UUID tenant, UUID toolId) { return kind(tenant, toolId).isPresent(); }

    /** @param principal 认证主体 @return 显式登记的固定目录；不创建任何Agent授权，不读取本地文件 */
    public synchronized List<ToolDefinition> install(PrincipalRef principal) {
        // 沿用ToolController创建/授权目录的tool:grant；登记仍需项目管理权限，且不会自动授予任何Agent。
        access.authorize(principal, "project:manage", null); access.authorize(principal, "tool:grant", null);
        try {
            return transaction.execute(status -> {
                var result = new ArrayList<ToolDefinition>();
                for (Kind kind : Kind.values()) {
                    ToolDefinition expected = definition(principal.tenantId(), kind);
                    var stored = tools.findByTenantAndId(principal.tenantId(), expected.id()).orElse(null);
                    if (stored == null) stored = tools.save(expected);
                    if (!matches(stored, expected)) throw new IllegalStateException("受管项目工具定义已变化，请核对原目录");
                    result.add(stored);
                }
                audit.appendControlled(principal.tenantId(), principal.principalId(), "PROJECT_TOOLS_INSTALL", "LOCAL_PROJECT_TOOLS", "-", "SUCCEEDED", "仅登记固定目录，未授予Agent");
                return List.copyOf(result);
            });
        } catch (RuntimeException failure) { throw access.infrastructure(principal, null, "PROJECT_TOOLS_INSTALL", failure); }
    }

    /** @return 仅保留当前已授权且固定语义仍一致的目录工具；新增grant不会改变已经冻结的Run集合 */
    List<ToolDefinition> select(UUID tenant, List<ToolDefinition> authorized) {
        return authorized.stream().filter(tool -> kind(tenant, tool.id()).map(kind -> matches(tool, definition(tenant, kind))).orElse(false)).toList();
    }
    boolean valid(ToolDefinition tool) { return kind(tool.tenantId(), tool.id()).map(kind -> matches(tool, definition(tool.tenantId(), kind))).orElse(false); }

    private ToolDefinition definition(UUID tenant, Kind kind) {
        // 既有tool_definitions.endpoint为NOT NULL；这里只保存固定逻辑标识，不是可联网或执行的地址。
        // 执行仍须通过精确租户工具ID与当前项目Run的专用治理入口，不能依据endpoint建立原生执行器。
        return new ToolDefinition(id(tenant, kind), tenant, kind.name, description(kind), ToolType.LOCAL, schema(kind), kind.risk,
                true, "local-project:"+kind.name(), "cm-agent-local-project", "cm-agent-local-project");
    }
    private static boolean matches(ToolDefinition actual, ToolDefinition expected) {
        return actual.id().equals(expected.id()) && actual.tenantId().equals(expected.tenantId()) && actual.name().equals(expected.name())
                && actual.type() == ToolType.LOCAL && actual.enabled() && actual.riskLevel() == expected.riskLevel()
                && Objects.equals(actual.endpoint(), expected.endpoint()) && actual.inputSchema().equals(expected.inputSchema())
                && actual.description().equals(expected.description());
    }
    private static String description(Kind kind) {
        return switch (kind) {
            case LIST -> "列出当前固定项目根内目录，默认排除私有、链接及生成目录；使用服务端续页。";
            case READ -> "显式读取本轮首次固定的项目UTF-8文本，返回原文件SHA256及脱敏续读点。用户引用提供版本时必须传expectedSha256；不一致须刷新引用或结束本轮，不能忽略版本。不能提交宿主绝对路径。";
            case SEARCH -> "在当前项目进行有界字面搜索；正文先脱敏，partial表示未完整扫描。";
            case TOOLCHAINS -> "查询当前部署明确允许的命令/构建工具链标识及程序名。空列表表示未开放命令，不能猜测EXE路径或使用宿主PATH。";
            case PROPOSE_CHANGE -> "生成零项目副作用的不可变文件变更提案，先读取原文SHA256；新建、替换、移动、删除和单层目录动作均需后续精确批准。";
            case APPLY_CHANGE -> "仅应用operationId/version/digest所指的原提案；必须逐调用HIGH审批，不接受补丁或批准布尔值。";
            case PROPOSE_UNDO -> "为成功操作生成新的条件撤销提案，拒绝当前文件内容或权限已变化的情况；仍须新HIGH批准。";
            case PROPOSE_COMMAND -> "为部署允许工具链生成精确命令/构建清单，不执行；命令继承OS账号权限，可能在目录外、网络及插件内产生副作用。";
            case RUN_COMMAND -> "对不可变清单进行HIGH逐调用审批后单次启动；不会重复派发失联或结果未知的命令。";
            case COMMAND_STATUS -> "只核对当前项目命令状态和脱敏输出快照；outputAvailable=false不代表空输出。";
            case CANCEL_COMMAND -> "精确停止本次命令的Job进程树，不操作其他进程；需继续查询直至确认退出。";
        };
    }
    private String schema(Kind kind) {
        var fields = new LinkedHashMap<String, Object>(); var required = new ArrayList<String>();
        switch (kind) {
            case LIST -> { fields.put("path", text()); fields.put("cursor", text()); fields.put("limit", integer(1, 200)); }
            case READ -> { fields.put("path", text()); fields.put("offset", integer(0, 2_097_152)); fields.put("maxBytes", integer(4, 65_536)); fields.put("expectedSha256",Map.of("type","string","pattern","^[0-9a-f]{64}$")); required.add("path"); }
            case SEARCH -> { fields.put("query", text()); fields.put("content", Map.of("type", "boolean")); required.add("query"); }
            case TOOLCHAINS -> { /* 固定空参数，不能从模型输入扩充部署工具链。 */ }
            case PROPOSE_CHANGE -> { fields.put("changes", Map.of("type", "array", "minItems", 1, "maxItems", 20,
                    "items", Map.of("type", "object", "additionalProperties", false, "properties", Map.of(
                            "kind", Map.of("type", "string", "enum", List.of("CREATE_FILE", "REPLACE_FILE", "DELETE_FILE", "MOVE_FILE", "CREATE_DIRECTORY", "DELETE_EMPTY_DIRECTORY")),
                            "path", text(), "target", text(), "content", Map.of("type", "string", "maxLength", 1_048_576), "expectedSha256", text()), "required", List.of("kind", "path")))); required.add("changes"); }
            case APPLY_CHANGE, RUN_COMMAND -> { fields.put("operationId", text()); fields.put("version", integer(1, Integer.MAX_VALUE)); fields.put("digest", text()); required.addAll(List.of("operationId", "version", "digest")); }
            case PROPOSE_UNDO, COMMAND_STATUS, CANCEL_COMMAND -> { fields.put("operationId", text()); required.add("operationId"); }
            case PROPOSE_COMMAND -> {
                fields.put("toolchainId", text()); fields.put("arguments", Map.of("type", "array", "maxItems", 128, "items", text()));
                fields.put("relativeDirectory", text()); fields.put("environment", Map.of("type", "object", "additionalProperties", text()));
                fields.put("timeoutSeconds", integer(1, 1800)); fields.put("maxOutputBytes", integer(1, 1_048_576));
                required.addAll(List.of("toolchainId", "arguments", "relativeDirectory", "environment", "timeoutSeconds", "maxOutputBytes"));
            }
        }
        try { return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(Map.of("type", "object", "additionalProperties", false, "properties", fields, "required", required)); }
        catch (Exception failure) { throw new IllegalStateException("固定项目工具输入模式无法构造"); }
    }
    private static Map<String, Object> text() { return Map.of("type", "string", "maxLength", 1024); }
    private static Map<String, Object> integer(int min, int max) { return Map.of("type", "integer", "minimum", min, "maximum", max); }
}
