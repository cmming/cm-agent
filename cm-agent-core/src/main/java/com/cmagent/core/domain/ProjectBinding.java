package com.cmagent.core.domain;

import java.util.UUID;

/** 会话或 Run 可固定的项目来源共同身份，不携带客户端路径或宿主目录。 */
public interface ProjectBinding {
    /** @return 可信租户 */
    UUID tenantId();

    /** @return 精确原主体 */
    String ownerId();

    /** @return 固定项目 */
    UUID projectId();

    /** @return 固定智能体 */
    UUID agentId();

    /** @return 固定会话 */
    UUID conversationId();

    /** @return 当前 Run；会话绑定为空 */
    UUID runId();

    /** @return 不可变来源协议值 */
    int protocol();
}
