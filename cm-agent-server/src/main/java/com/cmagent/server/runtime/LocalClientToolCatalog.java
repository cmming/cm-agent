package com.cmagent.server.runtime;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.security.OperationCategory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 每轮才注入 Agent 的四类本机客户端虚拟工具。
 *
 * <p>这些定义不进入普通工具目录、MCP 发布或调试入口。工具标识由租户和函数名稳定派生，
 * 类别与风险完全由本目录决定，不能接受模型声明覆盖。具体运行、设备和撤销状态仍由签名通道
 * 与单轮授权仓储复核。</p>
 */
public final class LocalClientToolCatalog {
    private static final String ID_PREFIX = "cm-agent-local-client-tool:";
    private static final String ENDPOINT_PREFIX = "cm-agent-local://";

    private LocalClientToolCatalog() { }

    /** 固定的本机客户端能力。 */
    public enum Kind {
        /** 有界目录枚举与文件读取。 */ READ_FILES("local_client_read_files",
                OperationCategory.READ_ONLY, ToolRiskLevel.LOW,
                "在本机用户账号权限内列出目录或分段读取文件"),
        /** 创建、覆盖、追加或删除文件。 */ CHANGE_FILES("local_client_change_files",
                OperationCategory.FILE_CHANGE, ToolRiskLevel.HIGH,
                "在本机用户账号权限内创建、覆盖、追加或删除文件"),
        /** 由本机网络环境发起 HTTP/HTTPS 请求。 */ NETWORK("local_client_network",
                OperationCategory.NETWORK, ToolRiskLevel.HIGH,
                "在浏览器所在电脑发起 HTTP 或 HTTPS 请求"),
        /** 启动用户明确授权、以参数数组表达的命令。 */ COMMAND("local_client_command",
                OperationCategory.COMMAND, ToolRiskLevel.HIGH,
                "在浏览器所在电脑以当前用户权限启动命令");

        private final String toolName;
        private final OperationCategory category;
        private final ToolRiskLevel risk;
        private final String description;

        Kind(String toolName, OperationCategory category, ToolRiskLevel risk, String description) {
            this.toolName = toolName;
            this.category = category;
            this.risk = risk;
            this.description = description;
        }

        String toolName() { return toolName; }
        OperationCategory category() { return category; }
    }

    /**
     * 返回当前租户的四个固定定义。
     *
     * @param tenantId 可信租户
     * @return 不可变工具定义
     */
    public static List<ToolDefinition> definitions(UUID tenantId) {
        Objects.requireNonNull(tenantId, "租户标识不能为空");
        return java.util.Arrays.stream(Kind.values()).map(kind -> new ToolDefinition(
                id(tenantId, kind), tenantId, kind.toolName, kind.description, ToolType.LOCAL,
                schema(kind), kind.risk, true, ENDPOINT_PREFIX + kind.name().toLowerCase(),
                "system:local-client", "system:local-client")).toList();
    }

    /** @return 当前租户和工具标识对应的固定能力；非本目录工具返回空 */
    public static java.util.Optional<Kind> find(UUID tenantId, UUID toolId) {
        if (tenantId == null || toolId == null) return java.util.Optional.empty();
        return java.util.Arrays.stream(Kind.values())
                .filter(kind -> id(tenantId, kind).equals(toolId)).findFirst();
    }

    /** @return 标识是否属于本租户本机客户端目录 */
    public static boolean managed(UUID tenantId, UUID toolId) {
        return find(tenantId, toolId).isPresent();
    }

    /** @return 工具固定类别 */
    public static OperationCategory category(UUID tenantId, UUID toolId) {
        return find(tenantId, toolId).map(Kind::category)
                .orElseThrow(() -> new IllegalArgumentException("不是本机客户端工具"));
    }

    /** @return 工具固定名称 */
    public static String name(UUID tenantId, UUID toolId) {
        return find(tenantId, toolId).map(Kind::toolName)
                .orElseThrow(() -> new IllegalArgumentException("不是本机客户端工具"));
    }

    private static UUID id(UUID tenantId, Kind kind) {
        return UUID.nameUUIDFromBytes((ID_PREFIX + tenantId + ":" + kind.name())
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String schema(Kind kind) {
        return switch (kind) {
            // AgentScope 2.0.2 的 oneOf 参数在不同模型适配器下会把整数降为字符串，导致调用在网关前失败；
            // 这里使用单一对象 Schema，并允许数字字符串由客户端按范围再次校验。
            case READ_FILES -> """
                    {"type":"object","properties":{
                      "action":{"type":"string","enum":["list","read"]},
                      "path":{"type":"string"},
                      "after":{"type":["string","null"]},
                      "offset":{"type":["integer","string"],"minimum":0},
                      "limit":{"type":["integer","string"],"minimum":1,"maximum":4194304}
                    },"required":["action","path","limit"],"additionalProperties":false}
                    """;
            case CHANGE_FILES -> """
                    {"type":"object","properties":{
                      "action":{"type":"string","enum":["create","replace","append","delete"]},
                      "path":{"type":"string"},
                      "expectedSize":{"type":["integer","string"],"minimum":0},
                      "contentBase64":{"type":"string"}
                    },"required":["action","path"],"additionalProperties":false}
                    """;
            case NETWORK -> """
                    {"type":"object","properties":{"method":{"type":"string"},"url":{"type":"string"},"headers":{"type":"object","additionalProperties":{"type":"string"}},"bodyBase64":{"type":"string"},"timeoutSeconds":{"type":"integer","minimum":1,"maximum":300},"responseLimit":{"type":"integer","minimum":1,"maximum":4194304}},"required":["method","url","headers","bodyBase64","timeoutSeconds","responseLimit"],"additionalProperties":false}
                    """;
            case COMMAND -> """
                    {"type":"object","properties":{"arguments":{"type":"array","minItems":1,"maxItems":128,"items":{"type":"string"}},"workingDirectory":{"type":"string"},"timeoutSeconds":{"type":"integer","minimum":1,"maximum":1800},"outputLimit":{"type":"integer","minimum":1,"maximum":1048576}},"required":["arguments","workingDirectory","timeoutSeconds","outputLimit"],"additionalProperties":false}
                    """;
        };
    }
}
