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

    private static ToolDefinition project(UUID tenant, String kind, String name, ToolRiskLevel risk) {
        UUID id = UUID.nameUUIDFromBytes(("cm-agent/local-project/v1/" + tenant + "/" + kind)
                .getBytes(StandardCharsets.UTF_8));
        return new ToolDefinition(id, tenant, name, "固定项目工具", ToolType.LOCAL,
                "{\"type\":\"object\",\"properties\":{}}", risk, true,
                "local-project:" + kind, "system", "system");
    }
}
