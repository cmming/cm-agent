package com.cmagent.server.service;

import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.HttpToolMethod;
import com.cmagent.core.domain.McpToolPublication;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolQueryServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HTTP_TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID LOCAL_TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Mock
    private ToolDefinitionRepository toolRepository;

    @Mock
    private HttpToolConfigRepository httpToolConfigRepository;

    @Mock
    private McpToolPublicationRepository mcpToolPublicationRepository;

    @Test
    void listsToolSummariesWithTenantScopedBulkConfigurationQueries() {
        ToolDefinition httpTool = tool(HTTP_TOOL_ID, "orders", ToolType.HTTP);
        ToolDefinition localTool = tool(LOCAL_TOOL_ID, "echo", ToolType.LOCAL);
        HttpToolConfig httpConfig = new HttpToolConfig(
                TENANT_ID, HTTP_TOOL_ID, HttpToolMethod.POST, "https://api.example.test/orders", "{}",
                List.of(), Map.of("X-Api-Key", "secret/orders/key"), Duration.ofSeconds(1)
        );
        McpToolPublication publication = new McpToolPublication(TENANT_ID, HTTP_TOOL_ID, true, "admin");
        List<UUID> toolIds = List.of(HTTP_TOOL_ID, LOCAL_TOOL_ID);
        when(toolRepository.listByTenant(TENANT_ID)).thenReturn(List.of(httpTool, localTool));
        when(httpToolConfigRepository.findByTenantAndToolIds(TENANT_ID, toolIds))
                .thenReturn(Map.of(HTTP_TOOL_ID, httpConfig));
        when(mcpToolPublicationRepository.findByTenantAndToolIds(TENANT_ID, toolIds))
                .thenReturn(Map.of(HTTP_TOOL_ID, publication));

        List<ToolSummary> summaries = new ToolQueryService(
                toolRepository, httpToolConfigRepository, mcpToolPublicationRepository
        ).listByTenant(TENANT_ID);

        assertThat(summaries).extracting(summary -> summary.tool().name()).containsExactly("orders", "echo");
        assertThat(summaries).first().satisfies(summary -> {
            assertThat(summary.httpConfig()).isEqualTo(httpConfig);
            assertThat(summary.mcpPublished()).isTrue();
        });
        assertThat(summaries).element(1).satisfies(summary -> {
            assertThat(summary.httpConfig()).isNull();
            assertThat(summary.mcpPublished()).isFalse();
        });
        verify(httpToolConfigRepository).findByTenantAndToolIds(TENANT_ID, toolIds);
        verify(mcpToolPublicationRepository).findByTenantAndToolIds(TENANT_ID, toolIds);
        verify(httpToolConfigRepository, never()).findByTenantAndToolId(TENANT_ID, HTTP_TOOL_ID);
        verify(mcpToolPublicationRepository, never()).findByTenantAndToolId(TENANT_ID, HTTP_TOOL_ID);
    }

    private static ToolDefinition tool(UUID id, String name, ToolType type) {
        return new ToolDefinition(
                id, TENANT_ID, name, "描述", type, "{}", ToolRiskLevel.LOW, true,
                type == ToolType.HTTP ? "https://api.example.test/orders" : "", "admin", "admin"
        );
    }
}
