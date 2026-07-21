package com.cmagent.server.runtime.http;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface HttpToolSecretProvider {
    /**
     * 解析租户 Secret。自定义实现必须响应线程中断，并为自身数据库、网络或文件 I/O 配置独立超时。
     */
    Optional<String> resolve(UUID tenantId, String secretRef);
}
