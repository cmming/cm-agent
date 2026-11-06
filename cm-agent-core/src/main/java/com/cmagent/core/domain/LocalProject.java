package com.cmagent.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 私有本机项目的长期元数据，不包含宿主路径、文件正文或命令环境。
 *
 * <p>项目根身份与节点在创建后不可变；换目录须新建项目，不能让旧会话或审批悄悄换根。
 * 归档只改变登记状态，不能删除本地目录；在途操作停止和权限由上层服务编排。</p>
 *
 * @param id 项目唯一标识
 * @param tenantId 来自可信认证上下文的租户
 * @param ownerId 精确主体标识，不做大小写折叠或去空白
 * @param name 项目展示名，1至120个字符，不用作文件路径
 * @param nodeId 本机执行节点标识，不是客户端地址
 * @param rootBindingId 受保护本机根登记引用，不接受外部宿主路径
 * @param rootIdentity 原生目录身份的SHA256摘要，绑定后不可替换
 * @param state 项目登记状态，不等价于当前目录/桌面实时可用性
 * @param version 条件更新版本，从1开始
 * @param createdAt 创建时间，不随重命名改变
 * @param updatedAt 最近元数据修改时间
 */
public record LocalProject(UUID id, UUID tenantId, String ownerId, String name, String nodeId,
        UUID rootBindingId, String rootIdentity, State state, long version, Instant createdAt, Instant updatedAt) {
    /** 登记生命周期；本机目录是否暂时不可访问由服务视图额外判断。 */
    public enum State {
        /** 项目可接受经授权的新会话和文件操作，仍需实时核验根与权限。 */
        ACTIVE,
        /** 项目已移除活动入口，历史保留，原目录和已有文件不删除。 */
        ARCHIVED
    }

    public LocalProject {
        Objects.requireNonNull(id, "项目标识不能为空");
        Objects.requireNonNull(tenantId, "租户不能为空");
        Objects.requireNonNull(rootBindingId, "根绑定不能为空");
        Objects.requireNonNull(state, "项目状态不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
        Objects.requireNonNull(updatedAt, "更新时间不能为空");
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 200
                || name == null || name.isBlank() || name.length() > 120
                || nodeId == null || nodeId.isBlank() || nodeId.length() > 200
                || rootIdentity == null || !rootIdentity.matches("[0-9a-f]{64}")
                || version < 1 || updatedAt.isBefore(createdAt))
            throw new IllegalArgumentException("本地项目元数据不合法");
    }

    /**
     * 生成仅重命名的下一版本，不改变根、主体或节点；实际CAS写入由Repository完成。
     * @param nextName 新展示名，不会触发文件系统重命名
     * @param now 可信服务端修改时间，不能倒退
     * @return 保留不可变身份的新版本
     */
    public LocalProject rename(String nextName, Instant now) {
        if (state != State.ACTIVE) throw new IllegalStateException("归档项目不能重命名");
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("项目更新时间不能倒退");
        return new LocalProject(id, tenantId, ownerId, nextName, nodeId, rootBindingId, rootIdentity,
                state, Math.addExact(version, 1), createdAt, now);
    }

    /**
     * 生成归档版本，不访问文件系统；重复归档幂等。
     * @param now 可信服务端归档时间
     * @return 新归档版本；已归档返回原对象
     */
    public LocalProject archive(Instant now) {
        if (state == State.ARCHIVED) return this;
        if (now.isBefore(updatedAt)) throw new IllegalArgumentException("项目更新时间不能倒退");
        return new LocalProject(id, tenantId, ownerId, name, nodeId, rootBindingId, rootIdentity,
                State.ARCHIVED, Math.addExact(version, 1), createdAt, now);
    }
}

