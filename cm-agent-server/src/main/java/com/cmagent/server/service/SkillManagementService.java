package com.cmagent.server.service;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentSkillBinding;
import com.cmagent.core.domain.SkillDefinition;
import com.cmagent.core.domain.SkillResource;
import com.cmagent.core.domain.SkillVersion;
import com.cmagent.core.domain.SkillVersionView;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.AgentSkillBindingRepository;
import com.cmagent.core.repository.SkillDefinitionRepository;
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
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 编排技能创建、版本更新、启停和 Agent 绑定，并把严格审计纳入同一工作单元。
 *
 * <p>所有 tenant 均取自认证主体，客户端不能覆盖。上传解析在调用本服务前完成，事务只覆盖
 * 领域校验和持久化；审计失败会使 memory 暂存状态不发布，并使 JDBC 事务回滚。</p>
 */
@Service
public class SkillManagementService {
    private final SkillDefinitionRepository definitions;
    private final SkillVersionRepository versions;
    private final SkillResourceRepository resources;
    private final AgentSkillBindingRepository bindings;
    private final AgentDefinitionRepository agents;
    private final SkillUnitOfWork workUnit;
    private final AuditAppender audit;
    private final SkillProperties properties;
    private final Clock clock;

    /** 创建技能管理服务并固定事务、安全和时间依赖。 */
    @Autowired
    public SkillManagementService(SkillDefinitionRepository definitions, SkillVersionRepository versions,
                                  SkillResourceRepository resources, AgentSkillBindingRepository bindings,
                                  AgentDefinitionRepository agents, SkillUnitOfWork workUnit,
                                  AuditAppender audit, SkillProperties properties) {
        this(definitions, versions, resources, bindings, agents, workUnit, audit, properties, Clock.systemUTC());
    }

    SkillManagementService(SkillDefinitionRepository definitions, SkillVersionRepository versions,
                           SkillResourceRepository resources, AgentSkillBindingRepository bindings,
                           AgentDefinitionRepository agents, SkillUnitOfWork workUnit,
                           AuditAppender audit, SkillProperties properties, Clock clock) {
        this.definitions = definitions;
        this.versions = versions;
        this.resources = resources;
        this.bindings = bindings;
        this.agents = agents;
        this.workUnit = workUnit;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** 创建默认停用的技能及首个不可变版本。 */
    public SkillVersionView create(PrincipalRef principal, ParsedSkillPackage parsed) {
        requireFeatureEnabled();
        return workUnit.execute(() -> {
            if (definitions.findByName(principal.tenantId(), parsed.name()).isPresent()) {
                throw conflict("同名技能已存在");
            }
            Instant now = clock.instant();
            UUID skillId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            SkillDefinition definition = new SkillDefinition(skillId, principal.tenantId(), parsed.name(),
                    versionId, false, 0, principal.principalId(), principal.principalId(), now, now);
            SkillVersion version = version(principal, parsed, skillId, versionId, 1, now);
            List<SkillResource> entries = resourceEntries(principal.tenantId(), skillId, versionId, parsed.resources());
            definitions.insert(definition);
            versions.insert(version);
            resources.insertAll(entries);
            appendAudit(principal, "SKILL_CREATE", skillId, "技能创建成功，初始状态为停用");
            return new SkillVersionView(definition, version, entries);
        });
    }

    /** 在锁定定义后创建新版本，并用预期版本指针防止覆盖并发更新。 */
    public SkillVersionView update(
            PrincipalRef principal, UUID skillId, UUID expectedVersionId, ParsedSkillPackage parsed) {
        requireFeatureEnabled();
        return workUnit.execute(() -> {
            SkillDefinition current = lockSkill(principal.tenantId(), skillId);
            if (!current.currentVersionId().equals(expectedVersionId)) {
                throw conflict("技能已被其他请求更新，请刷新后重试");
            }
            if (!current.name().equals(parsed.name())) {
                throw conflict("技能名称不可修改");
            }
            SkillVersion previous = requireVersion(current);
            if (previous.sha256().equals(parsed.sha256())) {
                return new SkillVersionView(current, previous,
                        resources.list(principal.tenantId(), skillId, previous.id()));
            }
            Instant now = clock.instant();
            UUID versionId = UUID.randomUUID();
            SkillVersion version = version(principal, parsed, skillId, versionId,
                    Math.addExact(previous.versionNo(), 1), now);
            List<SkillResource> entries = resourceEntries(principal.tenantId(), skillId, versionId, parsed.resources());
            versions.insert(version);
            resources.insertAll(entries);
            SkillDefinition next = new SkillDefinition(current.id(), current.tenantId(), current.name(),
                    versionId, current.enabled(), current.accessEpoch(), current.createdBy(),
                    principal.principalId(), current.createdAt(), now);
            if (!definitions.updateCurrent(next, expectedVersionId)) {
                throw conflict("技能已被其他请求更新，请刷新后重试");
            }
            appendAudit(principal, "SKILL_VERSION_CREATE", skillId, "技能版本更新成功");
            return new SkillVersionView(next, version, entries);
        });
    }

    /** 设置启停状态；只有从启用切到停用时递增访问纪元。 */
    public SkillDefinition setEnabled(PrincipalRef principal, UUID skillId, boolean enabled) {
        if (enabled) {
            requireFeatureEnabled();
        }
        return workUnit.execute(() -> {
            SkillDefinition current = lockSkill(principal.tenantId(), skillId);
            long epoch = current.enabled() && !enabled
                    ? Math.addExact(current.accessEpoch(), 1) : current.accessEpoch();
            SkillDefinition next = new SkillDefinition(current.id(), current.tenantId(), current.name(),
                    current.currentVersionId(), enabled, epoch, current.createdBy(), principal.principalId(),
                    current.createdAt(), clock.instant());
            definitions.updateEnabled(next);
            appendAudit(principal, enabled ? "SKILL_ENABLE" : "SKILL_DISABLE", skillId,
                    enabled ? "技能已启用" : "技能已停用");
            return next;
        });
    }

    /** 绑定已启用技能；锁定 Agent 后检查数量，重复请求返回原绑定。 */
    public AgentSkillBinding bind(PrincipalRef principal, UUID agentId, UUID skillId) {
        requireFeatureEnabled();
        return workUnit.execute(() -> {
            requireAgent(principal, agentId);
            lockAgent(principal.tenantId(), agentId);
            SkillDefinition skill = lockSkill(principal.tenantId(), skillId);
            if (!skill.enabled()) {
                throw revoked("技能已停用，不能绑定");
            }
            AgentSkillBinding existing = bindings.find(principal.tenantId(), agentId, skillId).orElse(null);
            if (existing != null) {
                return existing;
            }
            if (bindings.list(principal.tenantId(), agentId).size() >= properties.getMaxBoundSkills()) {
                throw conflict("Agent 绑定技能数量已达到上限");
            }
            AgentSkillBinding binding = new AgentSkillBinding(UUID.randomUUID(), principal.tenantId(),
                    agentId, skillId, principal.principalId(), clock.instant());
            bindings.insert(binding);
            appendAudit(principal, "AGENT_SKILL_BIND", agentId, "Agent 已绑定技能 " + skill.name());
            return binding;
        });
    }

    /** 幂等解绑；功能关闭时仍允许撤销已有授权。 */
    public void unbind(PrincipalRef principal, UUID agentId, UUID skillId) {
        workUnit.execute(() -> {
            requireAgent(principal, agentId);
            lockAgent(principal.tenantId(), agentId);
            if (bindings.delete(principal.tenantId(), agentId, skillId)) {
                appendAudit(principal, "AGENT_SKILL_UNBIND", agentId, "Agent 已解绑技能");
            }
            return null;
        });
    }

    private void requireAgent(PrincipalRef principal, UUID agentId) {
        if (agents.findByTenantAndId(principal.tenantId(), agentId).isEmpty()) {
            throw notFound("Agent 不存在");
        }
    }

    private SkillDefinition lockSkill(UUID tenantId, UUID skillId) {
        try {
            return definitions.lock(tenantId, skillId);
        } catch (NoSuchElementException ex) {
            throw notFound("技能不存在");
        }
    }

    private void lockAgent(UUID tenantId, UUID agentId) {
        try {
            bindings.lockAgent(tenantId, agentId);
        } catch (NoSuchElementException ex) {
            throw notFound("Agent 不存在");
        }
    }

    private SkillVersion requireVersion(SkillDefinition definition) {
        return versions.find(definition.tenantId(), definition.id(), definition.currentVersionId())
                .orElseThrow(() -> new SkillAccessException(ApiErrorCode.SKILL_SNAPSHOT_UNAVAILABLE,
                        "技能当前版本不可用", errorId(), true));
    }

    private SkillVersion version(PrincipalRef principal, ParsedSkillPackage parsed, UUID skillId,
                                 UUID versionId, int versionNo, Instant now) {
        return new SkillVersion(versionId, principal.tenantId(), skillId, versionNo,
                parsed.description(), parsed.metadata(), parsed.content(), parsed.sha256(),
                principal.principalId(), now);
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

    private static SkillAccessException conflict(String message) {
        return new SkillAccessException(ApiErrorCode.SKILL_CONFLICT, message, errorId(), false);
    }

    private static SkillAccessException revoked(String message) {
        return new SkillAccessException(ApiErrorCode.SKILL_ACCESS_REVOKED, message, errorId(), false);
    }

    private static SkillAccessException notFound(String message) {
        return new SkillAccessException(ApiErrorCode.SKILL_NOT_FOUND, message, errorId(), false);
    }

    private static String mediaType(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
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
