package com.cmagent.core.repository;

import com.cmagent.core.domain.RuntimeCheckpoint;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 定义 AgentScope 加密状态条目的租户隔离存储契约。
 *
 * <p>载荷进入 Repository 前必须已经加密。实现不得记录密文或将其写入普通消息、审计详情。</p>
 */
public interface RuntimeCheckpointRepository {
    /** 按状态槽和 key 覆盖保存一个加密条目。 */
    RuntimeCheckpoint save(RuntimeCheckpoint checkpoint);

    /** 查询一个未由调用方解密的检查点条目。 */
    Optional<RuntimeCheckpoint> find(UUID tenantId, String userId, String sessionId, String stateKey);

    /** 判断状态槽是否至少包含一个条目。 */
    boolean exists(UUID tenantId, String userId, String sessionId);

    /** 删除整个状态槽，用于 Run 终态、取消或过期清理。 */
    void deleteSession(UUID tenantId, String userId, String sessionId);

    /** 删除状态槽中的单个 key。 */
    void delete(UUID tenantId, String userId, String sessionId, String stateKey);

    /** 列出租户内指定用户的会话标识，供 AgentScope State Store 合同使用。 */
    Set<String> listSessionIds(UUID tenantId, String userId);
}
