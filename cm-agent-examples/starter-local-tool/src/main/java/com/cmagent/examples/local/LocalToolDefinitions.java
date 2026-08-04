package com.cmagent.examples.local;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;

import java.util.UUID;

/**
 * 提供 LOCAL 示例使用的固定工具定义。
 */
public final class LocalToolDefinitions {
    public static final UUID EXAMPLE_TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID ECHO_TOOL_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    public static final UUID ADD_TOOL_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");

    /**
     * 创建本地示例工具定义集合。
     */
    private LocalToolDefinitions() {
    }

    /**
     * 创建回显工具定义。
     */
    public static ToolDefinition echo() {
        return new ToolDefinition(
                ECHO_TOOL_ID,
                EXAMPLE_TENANT_ID,
                "echo",
                "回显非空消息",
                ToolType.LOCAL,
                """
                {"type":"object","properties":{"message":{"type":"string","minLength":1}},"required":["message"],"additionalProperties":false}
                """.strip(),
                ToolRiskLevel.LOW,
                true,
                "",
                "example",
                "example"
        );
    }

    /**
     * 创建精确加法工具定义。
     */
    public static ToolDefinition add() {
        return new ToolDefinition(
                ADD_TOOL_ID,
                EXAMPLE_TENANT_ID,
                "add",
                "对两个数字执行精确加法",
                ToolType.LOCAL,
                """
                {"type":"object","properties":{"left":{"type":"number"},"right":{"type":"number"}},"required":["left","right"],"additionalProperties":false}
                """.strip(),
                ToolRiskLevel.LOW,
                true,
                "",
                "example",
                "example"
        );
    }
}
