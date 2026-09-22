package com.cmagent.server.service;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.ApiPageRequest;
import com.cmagent.api.ApiPageResponse;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillResource;
import com.cmagent.core.domain.SkillRelease;
import com.cmagent.core.domain.SkillReleaseAction;
import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.AgentSkillBindingRepository;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillResourceRepository;
import com.cmagent.core.repository.SkillReleaseRepository;
import com.cmagent.core.repository.SkillVersionRepository;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.server.web.SkillResponses;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 在可信租户边界内组装技能管理查询，不根据当前目录猜测历史版本。 */
@Service
public class SkillQueryService {
    private final SkillDefinitionRepository definitions;
    private final SkillVersionRepository versions;
    private final SkillResourceRepository resources;
    private final AgentSkillBindingRepository bindings;
    private final AgentDefinitionRepository agents;
    private final SkillReleaseRepository releases;
    private final SkillVersionDiffService diffs;

    /**
     * @param definitions 技能定义仓储
     * @param versions 技能版本仓储
     * @param resources 技能资源仓储
     * @param bindings Agent 技能绑定仓储
     * @param agents Agent 定义仓储
     * @param releases 技能发布事实仓储
     * @param diffs 版本差异服务
     */
    public SkillQueryService(SkillDefinitionRepository definitions, SkillVersionRepository versions,
                             SkillResourceRepository resources, AgentSkillBindingRepository bindings,
                             AgentDefinitionRepository agents, SkillReleaseRepository releases,
                             SkillVersionDiffService diffs) {
        this.definitions = definitions;
        this.versions = versions;
        this.resources = resources;
        this.bindings = bindings;
        this.agents = agents;
        this.releases = releases;
        this.diffs = diffs;
    }

    /** 按当前主体租户分页列出技能摘要。 */
    public ApiPageResponse<SkillResponses.Summary> list(
            PrincipalRef principal, String query, Boolean enabled, ApiPageRequest page) {
        ApiPageResponse<SkillDefinition> result = definitions.list(principal.tenantId(), query, enabled, page);
        return new ApiPageResponse<>(result.items().stream().map(this::summary).toList(),
                result.total(), result.page(), result.size());
    }

    /** 返回当前版本详情；不存在与跨租户使用相同错误。 */
    public SkillResponses.Detail detail(PrincipalRef principal, UUID skillId) {
        SkillDefinition definition = requireDefinition(principal.tenantId(), skillId);
        SkillVersion version = requireVersion(definition);
        List<SkillResource> entries = resources.list(
                principal.tenantId(), definition.id(), definition.currentVersionId());
        return new SkillResponses.Detail(summary(definition), version.metadata(), version.content(),
                entries.stream().map(item -> new SkillResponses.ResourceEntry(
                        item.path(), item.mediaType(), item.byteLength())).toList(),
                bindings.countBySkill(principal.tenantId(), definition.id()));
    }

    /** 精确读取固定版本中的指令或资源文本。 */
    public SkillResponses.Resource resource(
            PrincipalRef principal, UUID skillId, UUID versionId, String path) {
        requireDefinition(principal.tenantId(), skillId);
        SkillVersion version = versions.find(principal.tenantId(), skillId, versionId)
                .orElseThrow(this::notFound);
        if ("SKILL.md".equals(path)) {
            return new SkillResponses.Resource(skillId, versionId, path, "text/markdown", version.content());
        }
        SkillResource resource = resources.list(principal.tenantId(), skillId, versionId).stream()
                .filter(item -> item.path().equals(path)).findFirst().orElseThrow(this::notFound);
        return new SkillResponses.Resource(skillId, versionId, path, resource.mediaType(), resource.content());
    }

    /** 返回当前租户 Agent 的绑定摘要。 */
    public List<SkillResponses.Binding> bindings(PrincipalRef principal, UUID agentId) {
        if (agents.findByTenantAndId(principal.tenantId(), agentId).isEmpty()) {
            throw notFound();
        }
        return bindings.list(principal.tenantId(), agentId).stream().map(this::binding).toList();
    }

    /** 按当前指针和发布事实推导技能版本历史状态。 */
    public List<SkillResponses.VersionSummary> versions(PrincipalRef principal, UUID skillId) {
        SkillDefinition definition = requireDefinition(principal.tenantId(), skillId);
        List<SkillRelease> history = releases.list(principal.tenantId(), skillId);
        return versions.list(principal.tenantId(), skillId).stream()
                .map(version -> new SkillResponses.VersionSummary(
                        version.id(), version.versionNo(), version.description(), version.sha256(),
                        version.createdBy(), version.createdAt(),
                        state(definition, history, version)))
                .toList();
    }

    /** 返回同一租户、同一技能下两个不可变版本的安全文本差异。 */
    public SkillResponses.VersionDiff diff(
            PrincipalRef principal, UUID skillId, UUID fromVersionId, UUID toVersionId) {
        return diffs.diff(principal, skillId, fromVersionId, toVersionId);
    }

    /** 将新写入的固定版本转换为详情响应。 */
    public SkillResponses.Detail detail(PrincipalRef principal, SkillDefinition definition) {
        return detail(principal, definition.id());
    }

    private SkillResponses.Binding binding(AgentSkillBinding binding) {
        SkillDefinition definition = requireDefinition(binding.tenantId(), binding.skillId());
        SkillVersion version = requireVersion(definition);
        return new SkillResponses.Binding(binding.id(), definition.id(), definition.name(),
                version.description(), version.versionNo(), definition.enabled());
    }

    private SkillResponses.Summary summary(SkillDefinition definition) {
        SkillVersion version = requireVersion(definition);
        return new SkillResponses.Summary(definition.id(), definition.name(), version.description(),
                definition.currentVersionId(), definition.candidateVersionId(), definition.publishedVersionId(),
                version.versionNo(), definition.enabled(), definition.updatedAt());
    }

    private SkillResponses.VersionState state(
            SkillDefinition definition, List<SkillRelease> history, SkillVersion version) {
        if (version.id().equals(definition.candidateVersionId())) {
            return SkillResponses.VersionState.CANDIDATE;
        }
        if (version.id().equals(definition.publishedVersionId())) {
            return SkillResponses.VersionState.CURRENT_PUBLISHED;
        }
        return history.stream()
                .filter(release -> release.action() == SkillReleaseAction.PUBLISH
                        || release.action() == SkillReleaseAction.ROLLBACK)
                .anyMatch(release -> version.id().equals(release.versionId()))
                ? SkillResponses.VersionState.PREVIOUSLY_PUBLISHED
                : SkillResponses.VersionState.UNPUBLISHED_HISTORY;
    }

    private SkillDefinition requireDefinition(UUID tenantId, UUID skillId) {
        return definitions.find(tenantId, skillId).orElseThrow(this::notFound);
    }

    private SkillVersion requireVersion(SkillDefinition definition) {
        return versions.find(definition.tenantId(), definition.id(), definition.currentVersionId())
                .orElseThrow(() -> new SkillAccessException(ApiErrorCode.SKILL_SNAPSHOT_UNAVAILABLE,
                        "技能当前版本不可用", UUID.randomUUID().toString(), true));
    }

    private SkillAccessException notFound() {
        return new SkillAccessException(ApiErrorCode.SKILL_NOT_FOUND,
                "技能或关联资源不存在", UUID.randomUUID().toString(), false);
    }
}
