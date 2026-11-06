package com.cmagent.core.repository;

import com.cmagent.core.domain.LocalProjectRoot;
import java.util.Optional;
import java.util.UUID;

/** 私有根加密记录；只允许新增，不提供换根或明文路径查询。 */
public interface LocalProjectRootRepository {
    /** @param root 由受信票据形成且已加密的根 @return 是否新增，重复不覆盖；基础设施错误继续抛出 */
    boolean insertRoot(LocalProjectRoot root);
    /** @return 当前tenant/精确owner的根，不可见与不存在均为空 */
    Optional<LocalProjectRoot> root(UUID tenantId, String ownerId, UUID rootId);
}

