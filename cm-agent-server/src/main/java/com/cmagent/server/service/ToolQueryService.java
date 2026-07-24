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
/** 查询工具及其授权信息，并将领域数据转换为控制台所需的摘要。 */
public class ToolQueryService {
    private final ToolDefinitionRepository toolRepository;
    private final HttpToolConfigRepository httpToolConfigRepository;
    private final McpToolPublicationRepository mcpToolPublicationRepository;
    /**
     * ToolQueryService：转换内部数据为目标表示。
     */
    public ToolQueryService(
            ToolDefinitionRepository toolRepository,
            HttpToolConfigRepository httpToolConfigRepository,
            McpToolPublicationRepository mcpToolPublicationRepository
    ) {
        this.toolRepository = toolRepository;
        this.httpToolConfigRepository = httpToolConfigRepository;
        this.mcpToolPublicationRepository = mcpToolPublicationRepository;
    }

    /**
     * 查询租户下的工具及其 HTTP 配置和 MCP 发布状态。
     *
     * @param tenantId 租户标识
     * @return 工具摘要列表
     */
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

    /**
     * 查询租户下指定工具的摘要。
     *
     * @param tenantId 租户标识
     * @param toolId   工具标识
     * @return 工具存在时返回摘要，否则返回空
     */
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
