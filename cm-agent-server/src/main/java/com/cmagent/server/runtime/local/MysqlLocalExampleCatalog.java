package com.cmagent.server.runtime.local;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.tool.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 提供 MySQL 调试环境可安装的固定 LOCAL 工具示例。
 */
@Component
public final class MysqlLocalExampleCatalog {
    public static final UUID EXAMPLE_TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID ECHO_TOOL_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    public static final UUID ADD_TOOL_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");

    private final List<LocalExample> examples;
    private final Map<String, LocalExample> examplesByKey;

    public MysqlLocalExampleCatalog(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        LocalExample echo = new LocalExample(
                "echo",
                new ToolDefinition(
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
                ),
                objectMapper.createObjectNode().put("message", "你好，CM Agent"),
                new EchoToolExecutor(objectMapper)
        );
        LocalExample add = new LocalExample(
                "add",
                new ToolDefinition(
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
                ),
                objectMapper.createObjectNode().put("left", 0.1).put("right", 0.2),
                new AddToolExecutor(objectMapper)
        );
        examples = List.of(echo, add);
        Map<String, LocalExample> byKey = new LinkedHashMap<>();
        examples.forEach(example -> byKey.put(example.key(), example));
        examplesByKey = Map.copyOf(byKey);
    }

    public List<LocalExample> list() {
        return examples;
    }

    public Optional<LocalExample> find(String key) {
        return Optional.ofNullable(examplesByKey.get(key));
    }

    /**
     * 表示一个经过审核的固定 LOCAL 工具示例。
     */
    public record LocalExample(
            String key,
            ToolDefinition definition,
            JsonNode sampleInput,
            ToolExecutor executor
    ) {
        public LocalExample {
            key = Objects.requireNonNull(key, "key 不能为空");
            definition = Objects.requireNonNull(definition, "definition 不能为空");
            sampleInput = Objects.requireNonNull(sampleInput, "sampleInput 不能为空").deepCopy();
            executor = Objects.requireNonNull(executor, "executor 不能为空");
        }

        @Override
        public JsonNode sampleInput() {
            return sampleInput.deepCopy();
        }

        /**
         * 复制固定定义，仅替换持久化审计主体。
         */
        public ToolDefinition persistentDefinition(String actor) {
            return new ToolDefinition(
                    definition.id(), definition.tenantId(), definition.name(), definition.description(),
                    definition.type(), definition.inputSchema(), definition.riskLevel(), true,
                    definition.endpoint(), actor, actor
            );
        }
    }
}
