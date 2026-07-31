package com.cmagent.server.service;

import com.cmagent.core.domain.HttpToolConfig;
import com.cmagent.core.domain.McpToolPublication;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.repository.HttpToolConfigRepository;
import com.cmagent.core.repository.McpToolPublicationRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.server.runtime.ToolRuntimeReadiness;
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
    private final ToolRuntimeReadiness toolRuntimeReadiness;
    /**
     * ToolQueryService：转换内部数据为目标表示。
     *
     * @param toolRepository 参与 ToolQueryService 处理的 toolRepository 输入值。
     * @param httpToolConfigRepository 参与 ToolQueryService 处理的 httpToolConfigRepository 输入值。
     * @param mcpToolPublicationRepository 参与 ToolQueryService 处理的 mcpToolPublicationRepository 输入值。
     * @param toolRuntimeReadiness 用于判断工具运行时是否就绪。
     */
    public ToolQueryService(
            ToolDefinitionRepository toolRepository,
            HttpToolConfigRepository httpToolConfigRepository,
            McpToolPublicationRepository mcpToolPublicationRepository,
            ToolRuntimeReadiness toolRuntimeReadiness
    ) {
        this.toolRepository = toolRepository;
        this.httpToolConfigRepository = httpToolConfigRepository;
        this.mcpToolPublicationRepository = mcpToolPublicationRepository;
        this.toolRuntimeReadiness = toolRuntimeReadiness;
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
                .map(tool -> toSummary(
                        tool,
                        httpConfigs.get(tool.id()),
                        publications.get(tool.id())
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
                .map(tool -> {
                    HttpToolConfig httpConfig = httpToolConfigRepository.findByTenantAndToolId(tenantId, toolId).orElse(null);
                    McpToolPublication publication = mcpToolPublicationRepository
                            .findByTenantAndToolId(tenantId, toolId)
                            .orElse(null);
                    return toSummary(tool, httpConfig, publication);
                });
    }

    /**
     * 将写命令返回的同一份持久化快照转换为响应摘要，不再发起第二次仓储查询。
     *
     * @param tool         写命令已提交的工具定义
     * @param httpConfig   写命令已提交的 HTTP 配置
     * @param mcpPublished 写命令已提交的 MCP 发布状态
     * @return 与本次命令一致的工具摘要
     */
    public ToolSummary summarize(ToolDefinition tool, HttpToolConfig httpConfig, boolean mcpPublished) {
        return toSummary(tool, httpConfig, mcpPublished);
    }

    private ToolSummary toSummary(
            ToolDefinition tool,
            HttpToolConfig httpConfig,
            McpToolPublication publication
    ) {
        return toSummary(tool, httpConfig, publication != null && publication.enabled());
    }

    private ToolSummary toSummary(ToolDefinition tool, HttpToolConfig httpConfig, boolean mcpPublished) {
        return new ToolSummary(tool, httpConfig, mcpPublished, toolRuntimeReadiness.isReady(tool, httpConfig));
    }
}
