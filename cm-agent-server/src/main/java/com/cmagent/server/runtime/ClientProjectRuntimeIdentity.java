package com.cmagent.server.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * 当前 Server 进程参与设备项目归属竞争时的随机身份。
 *
 * <p>该标识只在进程启动时生成，不能来自请求、配置或数据库。同一进程的任务入队、租约续期和
 * Run 收口必须复用它，使重启后的实例无法继续使用前代执行许可。</p>
 *
 * @param instanceId 当前进程的随机实例标识
 */
public record ClientProjectRuntimeIdentity(UUID instanceId) {
    public ClientProjectRuntimeIdentity {
        Objects.requireNonNull(instanceId, "客户端项目实例标识不能为空");
    }

    /** @return 新进程随机实例标识 */
    public static ClientProjectRuntimeIdentity random() {
        return new ClientProjectRuntimeIdentity(UUID.randomUUID());
    }
}
