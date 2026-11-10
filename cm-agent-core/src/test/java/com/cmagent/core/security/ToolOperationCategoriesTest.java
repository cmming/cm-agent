package com.cmagent.core.security;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ToolOperationCategoriesTest {
    @Test
    void 固定项目工具按实际副作用分类() {
        UUID tenant = UUID.randomUUID();

        assertThat(ToolOperationCategories.category(project(tenant, "LIST", "project_list_files",
                ToolRiskLevel.LOW))).contains(OperationCategory.READ_ONLY);
        assertThat(ToolOperationCategories.category(project(tenant, "PROPOSE_CHANGE", "project_propose_changes",
                ToolRiskLevel.MEDIUM))).contains(OperationCategory.READ_ONLY);
        assertThat(ToolOperationCategories.category(project(tenant, "APPLY_CHANGE", "project_apply_changes",
                ToolRiskLevel.HIGH))).contains(OperationCategory.FILE_CHANGE);
        assertThat(ToolOperationCategories.category(project(tenant, "RUN_COMMAND", "project_run_command",
                ToolRiskLevel.HIGH))).contains(OperationCategory.COMMAND);
        assertThat(ToolOperationCategories.category(project(tenant, "CANCEL_COMMAND", "project_cancel_command",
                ToolRiskLevel.MEDIUM))).contains(OperationCategory.COMMAND);
    }

    @Test
    void 同名或降低风险的伪造项目工具不获得授权语义() {
        UUID tenant = UUID.randomUUID();
        ToolDefinition exact = project(tenant, "RUN_COMMAND", "project_run_command", ToolRiskLevel.HIGH);
        ToolDefinition forgedId = new ToolDefinition(UUID.randomUUID(), exact.tenantId(), exact.name(), exact.description(),
                exact.type(), exact.inputSchema(), exact.riskLevel(), true, exact.endpoint(), exact.createdBy(), exact.updatedBy());
        ToolDefinition loweredRisk = new ToolDefinition(exact.id(), exact.tenantId(), exact.name(), exact.description(),
                exact.type(), exact.inputSchema(), ToolRiskLevel.LOW, true, exact.endpoint(), exact.createdBy(), exact.updatedBy());

        assertThat(ToolOperationCategories.category(forgedId)).isEmpty();
        assertThat(ToolOperationCategories.category(loweredRisk)).isEmpty();
    }

    @Test
    void 客户端项目工具只接受独立命名空间() {
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.nameUUIDFromBytes(("cm-agent/client-project/v1/" + tenant + "/READ")
                .getBytes(StandardCharsets.UTF_8));
        ToolDefinition exact = new ToolDefinition(id, tenant, "client_project_read_file", "读取",
                ToolType.LOCAL, "{\"type\":\"object\",\"properties\":{}}", ToolRiskLevel.LOW,
                true, "client-project:READ", "system", "system");
        ToolDefinition forged = new ToolDefinition(id, tenant, "project_read_file", "读取",
                ToolType.LOCAL, exact.inputSchema(), ToolRiskLevel.LOW, true,
                exact.endpoint(), "system", "system");

        assertThat(ToolOperationCategories.category(exact)).contains(OperationCategory.READ_ONLY);
        assertThat(ToolOperationCategories.category(forged)).isEmpty();
    }

    @Test
    void 客户端项目应用工具必须保持HIGH风险且归为文件变更() {
        UUID tenant = UUID.randomUUID();
        ToolDefinition apply = clientProject(tenant, "APPLY_CHANGE",
                "client_project_apply_changes", ToolRiskLevel.HIGH);
        ToolDefinition lowered = new ToolDefinition(apply.id(), apply.tenantId(), apply.name(),
                apply.description(), apply.type(), apply.inputSchema(), ToolRiskLevel.MEDIUM,
                true, apply.endpoint(), apply.createdBy(), apply.updatedBy());

        assertThat(ToolOperationCategories.category(apply))
                .contains(OperationCategory.FILE_CHANGE);
        assertThat(ToolOperationCategories.category(lowered)).isEmpty();
    }

    @Test void 客户端项目命令工具保持独立风险分类() {
        UUID tenant = UUID.randomUUID();
        assertThat(ToolOperationCategories.category(clientProject(tenant, "LIST_TOOLCHAINS",
                "client_project_list_toolchains", ToolRiskLevel.LOW)))
                .contains(OperationCategory.READ_ONLY);
        assertThat(ToolOperationCategories.category(clientProject(tenant, "PROPOSE_COMMAND",
                "client_project_propose_command", ToolRiskLevel.MEDIUM)))
                .contains(OperationCategory.READ_ONLY);
        assertThat(ToolOperationCategories.category(clientProject(tenant, "RUN_COMMAND",
                "client_project_run_command", ToolRiskLevel.HIGH)))
                .contains(OperationCategory.COMMAND);
        assertThat(ToolOperationCategories.category(clientProject(tenant, "COMMAND_STATUS",
                "client_project_command_status", ToolRiskLevel.LOW)))
                .contains(OperationCategory.READ_ONLY);
        assertThat(ToolOperationCategories.category(clientProject(tenant, "CANCEL_COMMAND",
                "client_project_cancel_command", ToolRiskLevel.MEDIUM)))
                .contains(OperationCategory.COMMAND);
    }

    private static ToolDefinition project(UUID tenant, String kind, String name, ToolRiskLevel risk) {
        UUID id = UUID.nameUUIDFromBytes(("cm-agent/local-project/v1/" + tenant + "/" + kind)
                .getBytes(StandardCharsets.UTF_8));
        return new ToolDefinition(id, tenant, name, "固定项目工具", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{}}", risk, true,
                "local-project:" + kind, "system", "system");
    }

    private static ToolDefinition clientProject(UUID tenant, String kind, String name,
            ToolRiskLevel risk) {
        UUID id = UUID.nameUUIDFromBytes(("cm-agent/client-project/v1/" + tenant + "/" + kind)
                .getBytes(StandardCharsets.UTF_8));
        return new ToolDefinition(id, tenant, name, "固定客户端项目工具", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{}}", risk, true,
                "client-project:" + kind, "system", "system");
    }
}
