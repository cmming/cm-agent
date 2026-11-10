package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 已配对设备上的长期项目来源，不包含客户端绝对路径、文件正文或设备私钥。
 *
 * <p>根标识与目录身份在创建后不可替换。归档只终止新的项目访问，不删除用户目录，
 * 也不清除可能仍用于停止、查询和对账的受限历史。</p>
 *
 * @param id Server 生成的项目标识
 * @param tenantId 认证主体所属可信租户
 * @param ownerId 精确原主体标识，不进行大小写或空白折叠
 * @param deviceId 固定执行设备
 * @param clientRootId 客户端生成并在该设备范围内持久化的不透明根标识
 * @param rootIdentity 客户端目录对象身份的 SHA-256 摘要
 * @param name 供项目页展示的受限名称，不参与客户端路径寻址
 * @param state 项目登记生命周期
 * @param version 条件更新版本，从 1 开始
 * @param createdAt Server 固定创建时间
 * @param updatedAt 最近元数据修改时间
 */
public record ClientProject(UUID id, UUID tenantId, String ownerId, UUID deviceId,
        UUID clientRootId, String rootIdentity, String name, State state, long version,
        Instant createdAt, Instant updatedAt) {
    /** 项目登记生命周期，目录实时身份和物理隔离另行判断。 */
    public enum State {
        /** 项目可接受新的设备任务，仍须逐次核验设备、根和权限。 */
        ACTIVE,
        /** 项目从活动入口移出，历史保留。 */
        ARCHIVED
    }

    public ClientProject {
        Objects.requireNonNull(id, "项目标识不能为空");
        Objects.requireNonNull(tenantId, "租户不能为空");
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        Objects.requireNonNull(clientRootId, "客户端根标识不能为空");
        Objects.requireNonNull(state, "项目状态不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
        Objects.requireNonNull(updatedAt, "更新时间不能为空");
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 200
                || rootIdentity == null || !rootIdentity.matches("[0-9a-f]{64}")
                || name == null || name.isBlank() || name.length() > 120
                || version < 1 || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("客户端项目元数据不合法");
        }
    }

    /**
     * 创建只修改展示名的下一版本。
     *
     * @param nextName 新的受限展示名，不触发客户端目录重命名
     * @param now Server 可信修改时间
     * @return 保留设备和根身份的下一版本
     */
    public ClientProject rename(String nextName, Instant now) {
        if (state != State.ACTIVE) throw new IllegalStateException("归档项目不能重命名");
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("项目更新时间不能倒退");
        return new ClientProject(id, tenantId, ownerId, deviceId, clientRootId, rootIdentity,
                nextName, state, Math.addExact(version, 1), createdAt, now);
    }

    /**
     * 创建归档版本，不访问设备或删除目录。
     *
     * @param now Server 可信归档时间
     * @return 归档版本；已经归档时返回当前对象
     */
    public ClientProject archive(Instant now) {
        if (state == State.ARCHIVED) return this;
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("项目更新时间不能倒退");
        return new ClientProject(id, tenantId, ownerId, deviceId, clientRootId, rootIdentity,
                name, State.ARCHIVED, Math.addExact(version, 1), createdAt, now);
    }
}
