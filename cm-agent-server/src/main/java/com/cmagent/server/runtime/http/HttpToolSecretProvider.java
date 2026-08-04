package com.cmagent.server.runtime.http;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
/** 受控解析 HTTP 工具所引用的 secret，不返回工具配置中的明文密钥。 */
public interface HttpToolSecretProvider {
    /**
     * 解析租户 Secret。自定义实现必须响应线程中断，并为自身数据库、网络或文件 I/O 配置独立超时。
     *
     * @param tenantId 当前租户标识，用于限定数据访问和隔离范围。
     * @param secretRef 不包含明文凭据的 Secret 引用。
     */
    Optional<String> resolve(UUID tenantId, String secretRef);
}
