package com.cmagent.agentscope;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.security.OperationAuthorizationMode;
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
    void 三种模式仅影响固定本机工具且普通高风险仍须审批() {
        UUID tenant = UUID.randomUUID();
        var read = local(tenant, "READ_FILES", "local_client_read_files", ToolRiskLevel.LOW);
        var file = local(tenant, "CHANGE_FILES", "local_client_change_files", ToolRiskLevel.HIGH);
        var network = local(tenant, "NETWORK", "local_client_network", ToolRiskLevel.HIGH);
        var command = local(tenant, "COMMAND", "local_client_command", ToolRiskLevel.HIGH);
        var business = tool("business_high", ToolRiskLevel.HIGH);
        var all = List.of(read, file, network, command, business);
        var effect = AgentScopeReActExecutor.permissionContext(all, true, true, OperationAuthorizationMode.ASK_ON_EFFECT);
        assertThat(effect.getAllowRules()).containsKey(read.name());
        assertThat(effect.getAskRules()).containsKeys(file.name(), network.name(), command.name(), business.name());
        var risk = AgentScopeReActExecutor.permissionContext(all, true, true, OperationAuthorizationMode.ASK_ON_RISK);
        assertThat(risk.getAllowRules()).containsKey(read.name());
        assertThat(risk.getAskRules()).containsKeys(file.name(), network.name(), command.name(), business.name());
        var full = AgentScopeReActExecutor.permissionContext(all, true, true, OperationAuthorizationMode.FULL_ACCESS);
        assertThat(full.getAllowRules()).containsKeys(read.name(), file.name(), network.name(), command.name());
        assertThat(full.getAskRules()).containsKey(business.name());
        var noApproval = AgentScopeReActExecutor.permissionContext(all, true, false, OperationAuthorizationMode.ASK_ON_EFFECT);
        assertThat(noApproval.getDenyRules()).containsKeys(file.name(), network.name(), command.name(), business.name());
    }

    @Test
    void 同名非固定工具不能借本机完全访问绕过审批() {
        var forged = tool("local_client_command", ToolRiskLevel.HIGH);
        var context = AgentScopeReActExecutor.permissionContext(List.of(forged), true, true,
                OperationAuthorizationMode.FULL_ACCESS);
        assertThat(context.getAskRules()).containsKey(forged.name());
    }

    @Test
    void 项目模式只作用于固定项目工具且普通高风险仍须审批() {
        UUID tenant = UUID.randomUUID();
        var read = project(tenant, "READ", "project_read_file", ToolRiskLevel.LOW, "READ_ONLY");
        var apply = project(tenant, "APPLY_CHANGE", "project_apply_changes", ToolRiskLevel.HIGH, "FILE_CHANGE");
        var command = project(tenant, "RUN_COMMAND", "project_run_command", ToolRiskLevel.HIGH, "COMMAND");
        var business = tool("business_high", ToolRiskLevel.HIGH);

        var effect = AgentScopeReActExecutor.permissionContext(List.of(read, apply, command, business), true, true,
                OperationAuthorizationMode.ASK_ON_EFFECT);
        assertThat(effect.getAllowRules()).containsKey(read.name());
        assertThat(effect.getAskRules()).containsKeys(apply.name(), command.name(), business.name());

        var full = AgentScopeReActExecutor.permissionContext(List.of(read, apply, command, business), true, true,
                OperationAuthorizationMode.FULL_ACCESS);
        assertThat(full.getAllowRules()).containsKeys(read.name(), apply.name(), command.name());
        assertThat(full.getAskRules()).containsKey(business.name());
    }

    private static ToolDefinition local(UUID tenant, String kind, String name, ToolRiskLevel risk) {
        UUID id = UUID.nameUUIDFromBytes(("cm-agent-local-client-tool:" + tenant + ":" + kind)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ToolDefinition(id, tenant, name, "测试工具", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{}}", risk, true,
                "cm-agent-local://" + kind.toLowerCase(java.util.Locale.ROOT), "tester", "tester");
    }

    private static ToolDefinition project(UUID tenant, String kind, String name, ToolRiskLevel risk,
                                          String category) {
        UUID id = UUID.nameUUIDFromBytes(("cm-agent/local-project/v1/" + tenant + "/" + kind)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ToolDefinition(id, tenant, name, "项目" + category + "工具", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{}}", risk, true,
                "local-project:" + kind, "tester", "tester");
    }

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
        assertThat(context.getAskRules()).isEmpty();
        assertThat(context.getDenyRules().get("high_tool")).singleElement()
                .extracting(rule -> rule.behavior()).isEqualTo(PermissionBehavior.DENY);
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
