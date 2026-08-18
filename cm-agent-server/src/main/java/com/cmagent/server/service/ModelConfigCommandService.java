package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.domain.ModelProviderType;
import com.cmagent.core.repository.ModelConfigRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.ModelCredentialCipher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 编排模型配置写操作、凭据加密、引用约束和安全审计。
 */
@Service
public class ModelConfigCommandService {
    private static final int LOCK_STRIPE_COUNT = 64;
    private static final UUID DEFAULT_MODEL_CONFIG_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final ReentrantLock[] LOCKS = createLocks();

    private final ModelConfigRepository repository;
    private final AuditAppender auditAppender;
    private final ModelCredentialCipher credentialCipher;
    private final TransactionTemplate transactionTemplate;

    public ModelConfigCommandService(
            ModelConfigRepository repository,
            AuditAppender auditAppender,
            ModelCredentialCipher credentialCipher,
            @Nullable TransactionTemplate transactionTemplate
    ) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.auditAppender = Objects.requireNonNull(auditAppender, "auditAppender 不能为空");
        this.credentialCipher = Objects.requireNonNull(credentialCipher, "credentialCipher 不能为空");
        this.transactionTemplate = transactionTemplate;
    }

    /** 创建模型配置，并在写入仓储前加密 API Key。 */
    public ModelConfig create(
            PrincipalRef principal,
            ModelProviderType providerType,
            String displayName,
            String baseUrl,
            String modelName,
            boolean enabled,
            String apiKey
    ) {
        ModelConfig modelConfig = new ModelConfig(
                UUID.randomUUID(), principal.tenantId(), providerType, displayName, baseUrl, modelName, enabled
        );
        if (transactionTemplate != null) {
            return requireResult(transactionTemplate.execute(status -> saveAndAudit(
                    principal, modelConfig, credentialCipher.encrypt(apiKey), "MODEL_CONFIG_CREATE")));
        }
        ModelConfig saved = repository.save(modelConfig, credentialCipher.encrypt(apiKey));
        try {
            appendAudit(principal, saved, "MODEL_CONFIG_CREATE", "已创建模型配置");
            return saved;
        } catch (RuntimeException exception) {
            repository.delete(principal.tenantId(), saved.id());
            throw exception;
        }
    }

    /** 更新当前租户内模型元数据，并可选择轮换 API Key。 */
    public ModelConfig update(
            PrincipalRef principal,
            UUID modelConfigId,
            ModelProviderType providerType,
            String displayName,
            String baseUrl,
            String modelName,
            boolean enabled,
            @Nullable String apiKey
    ) {
        return withLock(principal.tenantId(), modelConfigId, () -> {
            if (transactionTemplate != null) {
                return requireResult(transactionTemplate.execute(status -> updateAndAudit(
                        principal, modelConfigId, providerType, displayName, baseUrl, modelName, enabled, apiKey, true
                )));
            }
            return updateAndAudit(
                    principal, modelConfigId, providerType, displayName, baseUrl, modelName, enabled, apiKey, false);
        });
    }

    /** 删除未被 Agent 引用的模型配置。 */
    public void delete(PrincipalRef principal, UUID modelConfigId) {
        withLock(principal.tenantId(), modelConfigId, () -> {
            try {
                if (transactionTemplate != null) {
                    transactionTemplate.executeWithoutResult(status -> deleteAndAudit(principal, modelConfigId));
                } else {
                    deleteWithCompensation(principal, modelConfigId);
                }
            } catch (DataIntegrityViolationException exception) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "模型配置仍被 Agent 引用，请先调整 Agent 后再删除", exception);
            }
            return null;
        });
    }

    private ModelConfig saveAndAudit(
            PrincipalRef principal, ModelConfig modelConfig, String encryptedApiKey, String eventType
    ) {
        ModelConfig saved = repository.save(modelConfig, encryptedApiKey);
        appendAudit(principal, saved, eventType, "已创建模型配置");
        return saved;
    }

    private ModelConfig updateAndAudit(
            PrincipalRef principal,
            UUID modelConfigId,
            ModelProviderType providerType,
            String displayName,
            String baseUrl,
            String modelName,
            boolean enabled,
            @Nullable String apiKey,
            boolean lockForUpdate
    ) {
        ModelConfig existing = (lockForUpdate
                ? repository.findByTenantAndIdForUpdate(principal.tenantId(), modelConfigId)
                : repository.findByTenantAndId(principal.tenantId(), modelConfigId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型配置不存在"));
        ModelConfig updated = new ModelConfig(
                existing.id(), existing.tenantId(), providerType, displayName, baseUrl, modelName, enabled
        );
        String previousEncryptedApiKey = apiKey == null
                ? null
                : repository.findEncryptedApiKeyByTenantAndId(principal.tenantId(), modelConfigId).orElse(null);
        String encryptedApiKey = apiKey == null ? null : credentialCipher.encrypt(apiKey);
        try {
            repository.update(updated, encryptedApiKey);
            appendAudit(principal, updated, "MODEL_CONFIG_UPDATE", "已更新模型配置");
            return updated;
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模型配置不存在", exception);
        } catch (RuntimeException exception) {
            if (!lockForUpdate) {
                restore(existing, previousEncryptedApiKey, exception);
            }
            throw exception;
        }
    }

    private void deleteAndAudit(PrincipalRef principal, UUID modelConfigId) {
        ModelConfig existing = repository.findByTenantAndIdForUpdate(principal.tenantId(), modelConfigId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型配置不存在"));
        ensureNotReferenced(principal.tenantId(), modelConfigId);
        ensureNotSystemDefault(modelConfigId);
        if (!repository.delete(principal.tenantId(), modelConfigId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模型配置不存在");
        }
        appendAudit(principal, existing, "MODEL_CONFIG_DELETE", "已删除模型配置");
    }

    private void deleteWithCompensation(PrincipalRef principal, UUID modelConfigId) {
        ModelConfig existing = repository.findByTenantAndId(principal.tenantId(), modelConfigId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型配置不存在"));
        String encryptedApiKey = repository.findEncryptedApiKeyByTenantAndId(principal.tenantId(), modelConfigId)
                .orElse("not-configured");
        ensureNotReferenced(principal.tenantId(), modelConfigId);
        ensureNotSystemDefault(modelConfigId);
        if (!repository.delete(principal.tenantId(), modelConfigId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模型配置不存在");
        }
        try {
            appendAudit(principal, existing, "MODEL_CONFIG_DELETE", "已删除模型配置");
        } catch (RuntimeException exception) {
            try {
                repository.save(existing, encryptedApiKey);
            } catch (RuntimeException restoreFailure) {
                exception.addSuppressed(restoreFailure);
            }
            throw exception;
        }
    }

    private void ensureNotReferenced(UUID tenantId, UUID modelConfigId) {
        if (repository.isReferencedByAgent(tenantId, modelConfigId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "模型配置仍被 Agent 引用，请先调整 Agent 后再删除");
        }
    }

    private void ensureNotSystemDefault(UUID modelConfigId) {
        // 启动初始化器会按固定 ID 补齐该记录，禁止删除可避免“删除后重启又出现”的误导语义。
        if (DEFAULT_MODEL_CONFIG_ID.equals(modelConfigId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "系统默认模型配置不能删除，可停用或更新");
        }
    }

    private void appendAudit(PrincipalRef principal, ModelConfig modelConfig, String eventType, String message) {
        auditAppender.append(
                principal.tenantId(), principal.principalId(), eventType, "MODEL_CONFIG",
                modelConfig.id().toString(), "SUCCEEDED", message
        );
    }

    private void restore(ModelConfig existing, @Nullable String encryptedApiKey, RuntimeException originalFailure) {
        try {
            repository.update(existing, encryptedApiKey);
        } catch (RuntimeException restoreFailure) {
            originalFailure.addSuppressed(restoreFailure);
        }
    }

    private static ReentrantLock[] createLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPE_COUNT];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private <T> T withLock(UUID tenantId, UUID modelConfigId, Supplier<T> action) {
        int index = Math.floorMod(Objects.hash(tenantId, modelConfigId), LOCKS.length);
        ReentrantLock lock = LOCKS[index];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private static <T> T requireResult(T value) {
        return Objects.requireNonNull(value, "事务未返回模型配置结果");
    }
}
