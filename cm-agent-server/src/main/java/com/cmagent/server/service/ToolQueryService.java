package com.cmagent.server.service;

import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.McpToolPublication;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ToolQueryService {
    private final ToolDefinitionRepository toolRepository;
    private final HttpToolConfigRepository httpToolConfigRepository;
    private final McpToolPublicationRepository mcpToolPublicationRepository;

    public ToolQueryService(
            ToolDefinitionRepository toolRepository,
            HttpToolConfigRepository httpToolConfigRepository,
            McpToolPublicationRepository mcpToolPublicationRepository
    ) {
        this.toolRepository = toolRepository;
        this.httpToolConfigRepository = httpToolConfigRepository;
        this.mcpToolPublicationRepository = mcpToolPublicationRepository;
    }

    public List<ToolSummary> listByTenant(UUID tenantId) {
        List<ToolDefinition> tools = toolRepository.listByTenant(tenantId);
        if (tools.isEmpty()) {
            return List.of();
        }
        List<UUID> toolIds = tools.stream().map(ToolDefinition::id).toList();
        Map<UUID, HttpToolConfig> httpConfigs = httpToolConfigRepository.findByTenantAndToolIds(tenantId, toolIds);
        Map<UUID, McpToolPublication> publications = mcpToolPublicationRepository.findByTenantAndToolIds(tenantId, toolIds);
        return tools.stream()
                .map(tool -> new ToolSummary(
                        tool,
                        httpConfigs.get(tool.id()),
                        publications.containsKey(tool.id()) && publications.get(tool.id()).enabled()
                ))
                .toList();
    }

    public Optional<ToolSummary> findByTenantAndId(UUID tenantId, UUID toolId) {
        return toolRepository.findByTenantAndId(tenantId, toolId)
                .map(tool -> new ToolSummary(
                        tool,
                        httpToolConfigRepository.findByTenantAndToolId(tenantId, toolId).orElse(null),
                        mcpToolPublicationRepository.findByTenantAndToolId(tenantId, toolId)
                                .map(McpToolPublication::enabled)
                                .orElse(false)
                ));
    }
}
