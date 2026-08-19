package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.server.audit.AuditAppender;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 编排 Agent 的创建、编辑和删除，并将模型绑定限定为当前租户已启用的模型配置。
 */
@Service
public class AgentDefinitionCommandService {
    private static final int LOCK_STRIPE_COUNT = 64;
    private static final ReentrantLock[] LOCKS = createLocks();

    private final AgentDefinitionRepository agentRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final ToolGrantRepository toolGrantRepository;
    private final AuditAppender auditAppender;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建 Agent 命令服务。
     *
     * @param agentRepository Agent 定义仓储
     * @param modelConfigRepository 模型配置仓储
     * @param toolGrantRepository 工具授权仓储
     * @param auditAppender 严格审计写入器
     * @param transactionTemplate JDBC 模式下的事务模板；内存模式可为空
     */
    public AgentDefinitionCommandService(
            AgentDefinitionRepository agentRepository,
            ModelConfigRepository modelConfigRepository,
            ToolGrantRepository toolGrantRepository,
            AuditAppender auditAppender,
            @Nullable TransactionTemplate transactionTemplate
    ) {
        this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository 不能为空");
        this.modelConfigRepository = Objects.requireNonNull(modelConfigRepository, "modelConfigRepository 不能为空");
        this.toolGrantRepository = Objects.requireNonNull(toolGrantRepository, "toolGrantRepository 不能为空");
        this.auditAppender = Objects.requireNonNull(auditAppender, "auditAppender 不能为空");
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 创建 Agent，并从所选模型配置复制运行时模型名称。
     *
     * @param principal 当前可信认证主体
     * @param name Agent 名称
     * @param systemPrompt 系统提示词
     * @param modelConfigId 当前租户选择的模型配置标识
     * @param enabled 是否启用 Agent
     * @return 已创建的 Agent
     */
    public AgentDefinition create(
            PrincipalRef principal, String name, String systemPrompt, UUID modelConfigId, boolean enabled
    ) {
        if (transactionTemplate == null) {
            return createInternal(principal, name, systemPrompt, modelConfigId, enabled, false);
        }
        return requireResult(transactionTemplate.execute(status ->
                createInternal(principal, name, systemPrompt, modelConfigId, enabled, true)
        ));
    }

    /**
     * 兼容旧 API 客户端按模型名称创建 Agent；仅在当前租户恰有一个已启用配置
     * 使用该名称时才允许继续，避免根据客户端文本猜测供应商或跨租户绑定。
     * 新控制台始终应传入 {@code modelConfigId}。
     *
     * @param principal 当前可信认证主体
     * @param name Agent 名称
     * @param systemPrompt 系统提示词
     * @param legacyModelName 旧客户端传入的模型名称
     * @param enabled 是否启用 Agent
     * @return 已创建的 Agent
     */
    public AgentDefinition createByLegacyModelName(
            PrincipalRef principal, String name, String systemPrompt, String legacyModelName, boolean enabled
    ) {
        UUID modelConfigId = modelConfigRepository.listByTenant(principal.tenantId()).stream()
                .filter(ModelConfig::enabled)
                .filter(config -> config.modelName().equals(legacyModelName))
                .map(ModelConfig::id)
                .reduce((first, second) -> {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同名模型配置不唯一，请明确选择模型配置");
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "请从模型配置中选择模型"));
        return create(principal, name, systemPrompt, modelConfigId, enabled);
    }

    /**
     * 更新 Agent 的名称、系统提示词、启用状态及模型绑定。
     *
     * @param principal 当前可信认证主体
     * @param agentId 目标 Agent 标识
     * @param name Agent 名称
     * @param systemPrompt 系统提示词
     * @param modelConfigId 当前租户选择的模型配置标识
     * @param enabled 是否启用 Agent
     * @return 更新后的 Agent
     */
    public AgentDefinition update(
            PrincipalRef principal,
            UUID agentId,
            String name,
            String systemPrompt,
            UUID modelConfigId,
            boolean enabled
    ) {
        return withLock(principal.tenantId(), agentId, () -> {
            if (transactionTemplate == null) {
                return updateInternal(principal, agentId, name, systemPrompt, modelConfigId, enabled, false);
            }
            return requireResult(transactionTemplate.execute(status -> updateInternal(
                    principal, agentId, name, systemPrompt, modelConfigId, enabled, true
            )));
        });
    }

    /**
     * 删除未产生会话或运行历史的 Agent，同时清除其工具授权。
     *
     * <p>运行和会话记录属于审计链路的一部分，因此不能级联删除。已有历史时，
     * 调用方应改为停用 Agent。</p>
     *
     * @param principal 当前可信认证主体
     * @param agentId 目标 Agent 标识
     */
    public void delete(PrincipalRef principal, UUID agentId) {
        withLock(principal.tenantId(), agentId, () -> {
            if (transactionTemplate == null) {
                deleteInternal(principal, agentId);
            } else {
                transactionTemplate.executeWithoutResult(status -> deleteInternal(principal, agentId));
            }
            return null;
        });
    }

    private AgentDefinition createInternal(
            PrincipalRef principal,
            String name,
            String systemPrompt,
            UUID modelConfigId,
            boolean enabled,
            boolean lockModelConfig
    ) {
        ModelConfig modelConfig = requireEnabledModelConfig(principal.tenantId(), modelConfigId, lockModelConfig);
        AgentDefinition agent = new AgentDefinition(
                UUID.randomUUID(), principal.tenantId(), name, "", systemPrompt,
                modelConfig.id(), modelConfig.modelName(), 0.2d, 6, enabled,
                List.of(), principal.principalId(), principal.principalId()
        );
        AgentDefinition saved = agentRepository.save(agent);
        appendAudit(principal, saved, "AGENT_CREATE", "Agent 创建成功");
        return saved;
    }

    private AgentDefinition updateInternal(
            PrincipalRef principal,
            UUID agentId,
            String name,
            String systemPrompt,
            UUID modelConfigId,
            boolean enabled,
            boolean lockModelConfig
    ) {
        AgentDefinition existing = agentRepository.findByTenantAndId(principal.tenantId(), agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
        ModelConfig modelConfig = requireEnabledModelConfig(principal.tenantId(), modelConfigId, lockModelConfig);
        AgentDefinition updated = new AgentDefinition(
                existing.id(), existing.tenantId(), name, existing.description(), systemPrompt,
                modelConfig.id(), modelConfig.modelName(), existing.temperature(), existing.maxIterations(), enabled,
                existing.toolIds(), existing.createdBy(), principal.principalId()
        );
        agentRepository.update(updated);
        appendAudit(principal, updated, "AGENT_UPDATE", "Agent 已更新");
        return updated;
    }

    private void deleteInternal(PrincipalRef principal, UUID agentId) {
        AgentDefinition existing = agentRepository.findByTenantAndId(principal.tenantId(), agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
        if (agentRepository.hasUsageHistory(principal.tenantId(), agentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent 已有运行或会话历史，不能删除；请停用该 Agent");
        }
        toolGrantRepository.deleteByTenantAndAgentId(principal.tenantId(), agentId);
        if (!agentRepository.delete(principal.tenantId(), agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在");
        }
        appendAudit(principal, existing, "AGENT_DELETE", "Agent 已删除");
    }

    private ModelConfig requireEnabledModelConfig(UUID tenantId, UUID modelConfigId, boolean lockForUpdate) {
        ModelConfig modelConfig = (lockForUpdate
                ? modelConfigRepository.findByTenantAndIdForUpdate(tenantId, modelConfigId)
                : modelConfigRepository.findByTenantAndId(tenantId, modelConfigId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选模型配置不存在"));
        if (!modelConfig.enabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选模型配置已停用");
        }
        return modelConfig;
    }

    private void appendAudit(PrincipalRef principal, AgentDefinition agent, String eventType, String message) {
        auditAppender.append(
                principal.tenantId(), principal.principalId(), eventType, "AGENT",
                agent.id().toString(), "SUCCEEDED", message
        );
    }

    private <T> T withLock(UUID tenantId, UUID agentId, Supplier<T> action) {
        ReentrantLock lock = LOCKS[Math.floorMod(Objects.hash(tenantId, agentId), LOCKS.length)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock[] createLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPE_COUNT];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private static <T> T requireResult(T result) {
        return Objects.requireNonNull(result, "事务未返回 Agent");
    }
}
