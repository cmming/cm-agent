package com.cmagent.core.repository;

import com.cmagent.core.domain.ModelConfig;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 定义模型配置按租户保存、查询和删除的持久化契约。
 */
public interface ModelConfigRepository {

    /**
     * 保存新的模型配置；未提供密文时仅用于历史初始化或测试。
     *
     * @param modelConfig 待创建的模型配置
     * @return 已保存配置
     */
    ModelConfig save(ModelConfig modelConfig);

    /**
     * 保存新的模型配置及其已加密的 API Key。
     *
     * <p>调用方必须在进入仓储前完成加密。领域对象和普通查询均不携带密钥，
     * 防止凭据随模型元数据、审计或接口响应扩散。</p>
     *
     * @param modelConfig 待创建的模型配置
     * @param encryptedApiKey 已加密且带版本信息的 API Key 密文
     * @return 已保存配置
     */
    default ModelConfig save(ModelConfig modelConfig, String encryptedApiKey) {
        return save(modelConfig);
    }

    /**
     * 更新当前租户内已有模型配置。
     *
     * @param modelConfig 待更新的完整配置
     * @return 更新后的配置
     */
    ModelConfig update(ModelConfig modelConfig);

    /**
     * 更新模型元数据，并在非空时轮换已加密的 API Key。
     *
     * @param modelConfig 待更新的完整配置
     * @param encryptedApiKey 新密文；为 {@code null} 时保留原凭据
     * @return 更新后的配置
     */
    default ModelConfig update(ModelConfig modelConfig, String encryptedApiKey) {
        return update(modelConfig);
    }

    /**
     * 按租户和资源标识查询唯一记录。
      *
      * @param tenantId 当前租户标识
      * @param modelConfigId 模型配置标识
     */
    Optional<ModelConfig> findByTenantAndId(UUID tenantId, UUID modelConfigId);

    /**
     * 按租户读取运行时所需的 API Key 密文；绝不返回明文或将密文映射到领域对象。
     *
     * @param tenantId 当前租户标识
     * @param modelConfigId 模型配置标识
     * @return 匹配记录的密文
     */
    default Optional<String> findEncryptedApiKeyByTenantAndId(UUID tenantId, UUID modelConfigId) {
        return Optional.empty();
    }

    /**
     * 在事务内锁定并读取模型配置；内存实现可直接复用普通查询。
     *
     * @param tenantId 当前租户标识
     * @param modelConfigId 模型配置标识
     * @return 匹配的模型配置
     */
    default Optional<ModelConfig> findByTenantAndIdForUpdate(UUID tenantId, UUID modelConfigId) {
        return findByTenantAndId(tenantId, modelConfigId);
    }

    /**
     * 列出当前租户全部模型配置。
     *
     * @param tenantId 当前租户标识
     * @return 稳定排序后的配置列表
     */
    List<ModelConfig> listByTenant(UUID tenantId);

    /**
     * 判断模型配置是否仍被同租户 Agent 引用。
     *
     * @param tenantId 当前租户标识
     * @param modelConfigId 模型配置标识
     * @return 存在引用时为 {@code true}
     */
    boolean isReferencedByAgent(UUID tenantId, UUID modelConfigId);

    /**
     * 删除当前租户内的模型配置。
     *
     * @param tenantId 当前租户标识
     * @param modelConfigId 模型配置标识
     * @return 实际删除记录时为 {@code true}
     */
    boolean delete(UUID tenantId, UUID modelConfigId);
}
