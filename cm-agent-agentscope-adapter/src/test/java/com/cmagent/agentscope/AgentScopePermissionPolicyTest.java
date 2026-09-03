package com.cmagent.agentscope;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopePermissionPolicyTest {

    @Test
    void 启用权限引擎后仅高风险工具进入逐次询问() {
        var context = AgentScopeReActExecutor.permissionContext(List.of(
                tool("low_tool", ToolRiskLevel.LOW),
                tool("medium_tool", ToolRiskLevel.MEDIUM),
                tool("high_tool", ToolRiskLevel.HIGH)), true);

        assertThat(context.getMode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(context.getAllowRules()).containsKeys("low_tool", "medium_tool");
        assertThat(context.getAskRules()).containsKey("high_tool");
        assertThat(context.getAskRules().get("high_tool")).singleElement()
                .extracting(rule -> rule.behavior()).isEqualTo(PermissionBehavior.ASK);
    }

    @Test
    void 开关关闭时保持兼容并显式允许全部已授权工具() {
        var context = AgentScopeReActExecutor.permissionContext(
                List.of(tool("high_tool", ToolRiskLevel.HIGH)), false);

        assertThat(context.getAskRules()).isEmpty();
        assertThat(context.getAllowRules()).containsKey("high_tool");
    }

    @Test
    void 无会话运行使用不询问模式避免产生无人处理的暂停() {
        var context = AgentScopeReActExecutor.permissionContext(
                List.of(tool("high_tool", ToolRiskLevel.HIGH)), true, false);

        assertThat(context.getMode()).isEqualTo(PermissionMode.DONT_ASK);
        assertThat(context.getAskRules()).containsKey("high_tool");
    }

    @Test
    void 审批输入哈希不受Map插入顺序影响且参数变化会改变哈希() {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("orderId", "A-100");
        first.put("amount", 12);
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("amount", 12);
        reordered.put("orderId", "A-100");

        String expected = AgentScopeReActExecutor.approvalInputHash(objectMapper, first);

        assertThat(AgentScopeReActExecutor.approvalInputHash(objectMapper, reordered)).isEqualTo(expected);
        assertThat(AgentScopeReActExecutor.approvalInputHash(
                objectMapper, Map.of("orderId", "A-100", "amount", 13))).isNotEqualTo(expected);
        assertThat(expected).matches("[0-9a-f]{64}");
    }

    @Test
    void 审批参数摘要超过上限时截断并明确提示() {
        String summary = AgentScopeReActExecutor.approvalInputSummary("x".repeat(12_001));

        assertThat(summary).startsWith("x".repeat(12_000));
        assertThat(summary).endsWith("（内容超过展示上限，已截断）");
        assertThat(summary).hasSizeLessThan(12_050);
    }

    private static ToolDefinition tool(String name, ToolRiskLevel riskLevel) {
        return new ToolDefinition(
                UUID.randomUUID(), UUID.randomUUID(), name, "测试工具", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{}}", riskLevel, true, "", "tester", "tester");
    }
}
