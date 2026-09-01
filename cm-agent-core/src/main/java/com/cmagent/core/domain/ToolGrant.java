package com.cmagent.core.domain;

import java.util.UUID;

/**
 * 描述某个 Agent 对工具的租户内授权关系。
 *
 * <p>授权判断每次调用都必须重新执行（见 {@code DefaultToolAuthorizationPolicy}），
 * 这份数据是授权阶段的事实来源。</p>
 *
 * @param tenantId 授权关系归属租户，必须与工具、Agent 一致
 * @param toolId 被授权的工具标识
 * @param agentId 获得授权的 Agent 标识；授权粒度为 Agent 级
 * @param roleCode 角色/用途标记；空白表示纯 Agent 级授权，非空值当前仅作元数据
 * @param granted 是否授权；为 {@code false} 表示显式撤销记录
 */
public record ToolGrant(
        UUID tenantId,
        UUID toolId,
        UUID agentId,
        // 空白 roleCode 表示 Agent 级授权；非空值在当前阶段仅作为可选元数据保留，不参与匹配。
        String roleCode,
        boolean granted
) {
}
