package com.cmagent.core.repository;

import com.cmagent.core.domain.SandboxEndpoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** 每次操作限定可信租户；默认选择与端点修改在同一短原子边界内。 */
public interface SandboxEndpointRepository {
    /** @param tenantId 可信租户 @param operation 含严格审计的短操作 @param <T> 结果类型 @return 提交结果 */
    <T> T atomic(UUID tenantId, Supplier<T> operation);
    /** @param tenantId 可信租户 @return 未删除端点 */
    List<SandboxEndpoint> list(UUID tenantId);
    /** @param tenantId 可信租户 @param id 端点 @return 同租户未删除记录 */
    Optional<SandboxEndpoint> find(UUID tenantId, UUID id);
    /** @param endpoint 服务端构造的端点 */
    void insert(SandboxEndpoint endpoint);
    /** @param endpoint 新版本 @param expectedRevision 旧版本 @return 是否成功更新 */
    boolean update(SandboxEndpoint endpoint, long expectedRevision);
    /** @param tenantId 可信租户 @return 默认标识，不自动回退失效端点 */
    Optional<UUID> defaultId(UUID tenantId);
    /** @param tenantId 可信租户 @param endpointId 同租户端点，空表示显式取消默认 */
    void setDefault(UUID tenantId, UUID endpointId);
}
