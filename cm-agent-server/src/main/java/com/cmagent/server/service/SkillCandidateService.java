package com.cmagent.server.service;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillDependency;
import com.cmagent.core.domain.SkillResource;
import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.domain.SkillVersionView;
import com.cmagent.core.repository.SkillDefinitionRepository;
import com.cmagent.core.repository.SkillDependencyRepository;
import com.cmagent.core.repository.SkillResourceRepository;
import com.cmagent.core.repository.SkillVersionRepository;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.SkillProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * 维护技能候选版本指针；候选写入不会改动正式发布版本。
 */
@Service
public class SkillCandidateService {
    private final SkillDefinitionRepository definitions;
    private final SkillVersionRepository versions;
    private final SkillResourceRepository resources;
    private final SkillDependencyRepository dependencies;
    private final SkillUnitOfWork workUnit;
    private final AuditAppender audit;
    private final SkillProperties properties;
    private final Clock clock;

    /** 创建候选版本服务并固定事务、安全与时间依赖。 */
    @Autowired
    public SkillCandidateService(SkillDefinitionRepository definitions, SkillVersionRepository versions,
                                 SkillResourceRepository resources, SkillDependencyRepository dependencies,
                                 SkillUnitOfWork workUnit, AuditAppender audit, SkillProperties properties) {
        this(definitions, versions, resources, dependencies, workUnit, audit, properties, Clock.systemUTC());
    }

    SkillCandidateService(SkillDefinitionRepository definitions, SkillVersionRepository versions,
                          SkillResourceRepository resources, SkillDependencyRepository dependencies,
                          SkillUnitOfWork workUnit, AuditAppender audit, SkillProperties properties, Clock clock) {
        this.definitions = definitions;
        this.versions = versions;
        this.resources = resources;
        this.dependencies = dependencies;
        this.workUnit = workUnit;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** 创建只有候选指针的默认停用技能。 */
    public SkillVersionView createCandidate(PrincipalRef principal, ParsedSkillPackage parsed) {
        requireFeatureEnabled();
        return workUnit.execute(() -> {
            if (definitions.findByName(principal.tenantId(), parsed.name()).isPresent()) {
                throw conflict(ApiErrorCode.SKILL_CONFLICT, "同名技能已存在");
            }
            Instant now = clock.instant();
            UUID skillId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            SkillDefinition definition = new SkillDefinition(
                    skillId, principal.tenantId(), parsed.name(), versionId, null, false, 0, 0,
                    principal.principalId(), principal.principalId(), now, now);
            SkillVersion version = version(principal, parsed, skillId, versionId, 1, now);
            List<SkillResource> entries = resourceEntries(principal.tenantId(), skillId, versionId, parsed.resources());
            definitions.insert(definition);
            versions.insert(version);
            resources.insertAll(entries);
            dependencies.insertAll(dependencyEntries(principal.tenantId(), skillId, versionId, parsed.dependencies()));
            appendAudit(principal, "SKILL_CANDIDATE_CREATE", skillId, "候选版本已创建");
            return new SkillVersionView(definition, version, entries);
        });
    }

    /** 使用候选与发布双指针 CAS 替换候选版本。 */
    public SkillVersionView replaceCandidate(
            PrincipalRef principal, UUID skillId, UUID expectedCandidateId, UUID expectedPublishedId,
            ParsedSkillPackage parsed) {
        requireFeatureEnabled();
        return workUnit.execute(() -> {
            SkillDefinition current = lockSkill(principal.tenantId(), skillId);
            if (!Objects.equals(current.candidateVersionId(), expectedCandidateId)
                    || !Objects.equals(current.publishedVersionId(), expectedPublishedId)) {
                throw conflict(ApiErrorCode.SKILL_CANDIDATE_CONFLICT, "候选版本已变化，请刷新后重试");
            }
            if (!current.name().equals(parsed.name())) {
                throw conflict(ApiErrorCode.SKILL_CONFLICT, "技能名称不可修改");
            }
            UUID baselineId = current.candidateVersionId() == null
                    ? current.publishedVersionId() : current.candidateVersionId();
            SkillVersion baseline = baselineId == null ? null
                    : versions.find(principal.tenantId(), skillId, baselineId).orElse(null);
            if (baseline != null && baseline.sha256().equals(parsed.sha256())) {
                return new SkillVersionView(current, baseline,
                        resources.list(principal.tenantId(), skillId, baseline.id()));
            }
            Instant now = clock.instant();
            UUID versionId = UUID.randomUUID();
            int nextVersionNo = nextVersionNo(principal.tenantId(), skillId);
            SkillVersion version = version(principal, parsed, skillId, versionId, nextVersionNo, now);
            List<SkillResource> entries = resourceEntries(principal.tenantId(), skillId, versionId, parsed.resources());
            versions.insert(version);
            resources.insertAll(entries);
            dependencies.insertAll(dependencyEntries(principal.tenantId(), skillId, versionId, parsed.dependencies()));
            SkillDefinition next = new SkillDefinition(
                    current.id(), current.tenantId(), current.name(), versionId, current.publishedVersionId(),
                    current.enabled(), current.accessEpoch(), current.dependencyMappingRevision(),
                    current.createdBy(), principal.principalId(), current.createdAt(), now);
            if (!definitions.updatePointers(next, expectedCandidateId, expectedPublishedId)) {
                throw conflict(ApiErrorCode.SKILL_CANDIDATE_CONFLICT, "候选版本已变化，请刷新后重试");
            }
            appendAudit(principal, "SKILL_CANDIDATE_REPLACE", skillId, "候选版本已替换");
            return new SkillVersionView(next, version, entries);
        });
    }

    private int nextVersionNo(UUID tenantId, UUID skillId) {
        return versions.list(tenantId, skillId).stream()
                .mapToInt(SkillVersion::versionNo)
                .max()
                .orElse(0) + 1;
    }

    private SkillDefinition lockSkill(UUID tenantId, UUID skillId) {
        try {
            return definitions.lock(tenantId, skillId);
        } catch (NoSuchElementException ex) {
            throw notFound("技能不存在");
        }
    }

    private SkillVersion version(PrincipalRef principal, ParsedSkillPackage parsed, UUID skillId,
                                 UUID versionId, int versionNo, Instant now) {
        return new SkillVersion(versionId, principal.tenantId(), skillId, versionNo,
                parsed.description(), parsed.metadata(), parsed.content(), parsed.sha256(),
                principal.principalId(), now);
    }

    private List<SkillDependency> dependencyEntries(
            UUID tenantId, UUID skillId, UUID versionId, List<ParsedSkillDependency> source) {
        return source.stream().map(item -> new SkillDependency(
                tenantId, skillId, versionId, item.logicalKey(), item.required(), item.description(), item.position()))
                .toList();
    }

    private List<SkillResource> resourceEntries(
            UUID tenantId, UUID skillId, UUID versionId, Map<String, String> source) {
        return source.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            return new SkillResource(tenantId, skillId, versionId, entry.getKey(),
                    mediaType(entry.getKey()), entry.getValue(), bytes.length, sha256(bytes));
        }).toList();
    }

    private void appendAudit(PrincipalRef principal, String eventType, UUID resourceId, String message) {
        audit.append(principal.tenantId(), principal.principalId(), eventType,
                "SKILL", resourceId.toString(), "SUCCEEDED", message);
    }

    private void requireFeatureEnabled() {
        if (!properties.isEnabled()) {
            throw new SkillAccessException(ApiErrorCode.SKILL_FEATURE_DISABLED,
                    "技能功能未启用", errorId(), false);
        }
    }

    private static SkillAccessException conflict(ApiErrorCode code, String message) {
        return new SkillAccessException(code, message, errorId(), false);
    }

    private static SkillAccessException notFound(String message) {
        return new SkillAccessException(ApiErrorCode.SKILL_NOT_FOUND, message, errorId(), false);
    }

    private static String mediaType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/yaml";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".md")) return "text/markdown";
        return "text/plain";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 缺少 SHA-256", ex);
        }
    }

    private static String errorId() {
        return UUID.randomUUID().toString();
    }
}
