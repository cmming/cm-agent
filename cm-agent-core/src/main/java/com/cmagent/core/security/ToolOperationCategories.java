package com.cmagent.core.security;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 从服务端固定工具定义计算可信操作类别。
 *
 * <p>模型输出、客户端提交的名称和普通工具定义均不能证明副作用。只有租户绑定的固定工具
 * 标识、名称、类型、端点及风险同时匹配时才返回类别；项目工具与设备工具使用不同命名空间，
 * 不能用同名定义互相借用自动授权。</p>
 */
public final class ToolOperationCategories {
    private ToolOperationCategories() {
    }

    /**
     * 计算固定工具的可信操作类别。
     *
     * @param tool 当前服务端授权快照中的工具定义
     * @return 仅在固定设备或项目工具合同完全匹配时返回类别
     */
    public static Optional<OperationCategory> category(ToolDefinition tool) {
        Objects.requireNonNull(tool, "工具定义不能为空");
        Optional<OperationCategory> localClient = localClient(tool);
        return localClient.isPresent() ? localClient : localProject(tool);
    }

    private static Optional<OperationCategory> localClient(ToolDefinition tool) {
        String kind;
        OperationCategory category;
        ToolRiskLevel risk;
        switch (tool.name()) {
            case "local_client_read_files" -> {
                kind = "READ_FILES";
                category = OperationCategory.READ_ONLY;
                risk = ToolRiskLevel.LOW;
            }
            case "local_client_change_files" -> {
                kind = "CHANGE_FILES";
                category = OperationCategory.FILE_CHANGE;
                risk = ToolRiskLevel.HIGH;
            }
            case "local_client_network" -> {
                kind = "NETWORK";
                category = OperationCategory.NETWORK;
                risk = ToolRiskLevel.HIGH;
            }
            case "local_client_command" -> {
                kind = "COMMAND";
                category = OperationCategory.COMMAND;
                risk = ToolRiskLevel.HIGH;
            }
            default -> {
                return Optional.empty();
            }
        }
        UUID expected = UUID.nameUUIDFromBytes(("cm-agent-local-client-tool:" + tool.tenantId() + ":" + kind)
                .getBytes(StandardCharsets.UTF_8));
        boolean exact = expected.equals(tool.id()) && tool.type() == ToolType.LOCAL && tool.riskLevel() == risk
                && ("cm-agent-local://" + kind.toLowerCase(Locale.ROOT)).equals(tool.endpoint());
        return exact ? Optional.of(category) : Optional.empty();
    }

    private static Optional<OperationCategory> localProject(ToolDefinition tool) {
        ProjectKind kind;
        switch (tool.name()) {
            case "project_list_files" -> kind = project("LIST", ToolRiskLevel.LOW, OperationCategory.READ_ONLY);
            case "project_read_file" -> kind = project("READ", ToolRiskLevel.LOW, OperationCategory.READ_ONLY);
            case "project_search_files" -> kind = project("SEARCH", ToolRiskLevel.LOW, OperationCategory.READ_ONLY);
            case "project_list_toolchains" ->
                    kind = project("TOOLCHAINS", ToolRiskLevel.LOW, OperationCategory.READ_ONLY);
            case "project_propose_changes" ->
                    kind = project("PROPOSE_CHANGE", ToolRiskLevel.MEDIUM, OperationCategory.READ_ONLY);
            case "project_apply_changes" ->
                    kind = project("APPLY_CHANGE", ToolRiskLevel.HIGH, OperationCategory.FILE_CHANGE);
            case "project_propose_undo" ->
                    kind = project("PROPOSE_UNDO", ToolRiskLevel.MEDIUM, OperationCategory.READ_ONLY);
            case "project_propose_command" ->
                    kind = project("PROPOSE_COMMAND", ToolRiskLevel.MEDIUM, OperationCategory.READ_ONLY);
            case "project_run_command" ->
                    kind = project("RUN_COMMAND", ToolRiskLevel.HIGH, OperationCategory.COMMAND);
            case "project_command_status" ->
                    kind = project("COMMAND_STATUS", ToolRiskLevel.LOW, OperationCategory.READ_ONLY);
            case "project_cancel_command" ->
                    kind = project("CANCEL_COMMAND", ToolRiskLevel.MEDIUM, OperationCategory.COMMAND);
            default -> {
                return Optional.empty();
            }
        }
        UUID expected = UUID.nameUUIDFromBytes(
                ("cm-agent/local-project/v1/" + tool.tenantId() + "/" + kind.idName())
                        .getBytes(StandardCharsets.UTF_8));
        boolean exact = expected.equals(tool.id()) && tool.type() == ToolType.LOCAL
                && tool.riskLevel() == kind.risk()
                && ("local-project:" + kind.idName()).equals(tool.endpoint());
        return exact ? Optional.of(kind.category()) : Optional.empty();
    }

    private static ProjectKind project(String idName, ToolRiskLevel risk, OperationCategory category) {
        return new ProjectKind(idName, risk, category);
    }

    /**
     * 固定项目工具的标识名、风险及副作用类别。
     *
     * @param idName 标识派生和端点使用的固定枚举名
     * @param risk 最低可信风险
     * @param category 授权模式的判定类别
     */
    private record ProjectKind(String idName, ToolRiskLevel risk, OperationCategory category) {
    }
}
