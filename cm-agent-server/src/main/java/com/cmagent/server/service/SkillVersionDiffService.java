package com.cmagent.server.service;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillResource;
import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillResourceRepository;
import com.cmagent.core.repository.SkillVersionRepository;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.server.web.SkillResponses;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 在不引入外部 diff 依赖的前提下比较两个不可变技能版本。 */
@Service
public class SkillVersionDiffService {
    private final SkillDefinitionRepository definitions;
    private final SkillVersionRepository versions;
    private final SkillResourceRepository resources;

    /**
     * @param definitions 技能定义仓储
     * @param versions 技能版本仓储
     * @param resources 技能文本资源仓储
     */
    public SkillVersionDiffService(SkillDefinitionRepository definitions, SkillVersionRepository versions,
                                   SkillResourceRepository resources) {
        this.definitions = definitions;
        this.versions = versions;
        this.resources = resources;
    }

    /** 返回两个指定版本的只读安全文本差异。 */
    public SkillResponses.VersionDiff diff(
            PrincipalRef principal, UUID skillId, UUID fromVersionId, UUID toVersionId) {
        SkillDefinition definition = definitions.find(principal.tenantId(), skillId)
                .orElseThrow(this::notFound);
        SkillVersion from = requireVersion(definition, fromVersionId);
        SkillVersion to = requireVersion(definition, toVersionId);
        List<SkillResponses.ResourceDiff> entries = diffResources(
                principal.tenantId(), skillId, fromVersionId, toVersionId);
        return new SkillResponses.VersionDiff(skillId, fromVersionId, toVersionId,
                diffPart(from.content(), to.content()), entries);
    }

    private List<SkillResponses.ResourceDiff> diffResources(
            UUID tenantId, UUID skillId, UUID fromVersionId, UUID toVersionId) {
        Map<String, SkillResource> left = byPath(resources.list(tenantId, skillId, fromVersionId));
        Map<String, SkillResource> right = byPath(resources.list(tenantId, skillId, toVersionId));
        List<String> paths = new ArrayList<>(left.keySet());
        right.keySet().stream().filter(path -> !left.containsKey(path)).forEach(paths::add);
        paths.sort(String::compareTo);
        return paths.stream().map(path -> {
            SkillResource before = left.get(path);
            SkillResource after = right.get(path);
            String beforeText = before == null ? null : before.content();
            String afterText = after == null ? null : after.content();
            return new SkillResponses.ResourceDiff(path, state(beforeText, afterText), beforeText, afterText);
        }).toList();
    }

    private SkillVersion requireVersion(SkillDefinition definition, UUID versionId) {
        return versions.find(definition.tenantId(), definition.id(), versionId)
                .orElseThrow(this::notFound);
    }

    private static Map<String, SkillResource> byPath(List<SkillResource> source) {
        Map<String, SkillResource> result = new LinkedHashMap<>();
        source.forEach(item -> result.put(item.path(), item));
        return result;
    }

    private static SkillResponses.DiffPart diffPart(String before, String after) {
        return new SkillResponses.DiffPart(state(before, after), before, after);
    }

    private static SkillResponses.DiffState state(String before, String after) {
        if (before == null && after == null) {
            return SkillResponses.DiffState.UNCHANGED;
        }
        if (before == null) {
            return SkillResponses.DiffState.ADDED;
        }
        if (after == null) {
            return SkillResponses.DiffState.REMOVED;
        }
        return before.equals(after) ? SkillResponses.DiffState.UNCHANGED : SkillResponses.DiffState.CHANGED;
    }

    private SkillAccessException notFound() {
        return new SkillAccessException(ApiErrorCode.SKILL_NOT_FOUND,
                "技能或版本不存在", UUID.randomUUID().toString(), false);
    }
}
