package com.cmagent.server.runtime;

import java.util.Map;

/** 控制面探测与执行面共享相同Docker传输，避免探测通过但执行使用其他主机。 */
public final class DockerSkillSandboxConnection {
    private DockerSkillSandboxConnection(){}
    /** @param parameters 可信配置及短期认证材料，禁止日志 @return 调用方负责关闭的连接 */
    public static DockerDaemonConnection open(Map<String,String> parameters) {
        return parameters.isEmpty()?DockerDaemonConnection.legacy():DockerSkillSandbox.connection(parameters);
    }
}
