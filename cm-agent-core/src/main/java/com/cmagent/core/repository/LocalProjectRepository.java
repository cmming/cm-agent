package com.cmagent.core.repository;

import com.cmagent.core.domain.LocalProject;
import com.cmagent.core.domain.LocalProjectPageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 本机私有项目的长期存储；不提供按客户端路径查询或更换根的接口。
 *
 * <p>所有实现必须以可信tenant与精确owner同时过滤，不赋予同租户管理员隐式访问。
 * JDBC写入与严格审计由服务共享短事务，文件/原生窗口/进程不得在事务内执行。
 * JDBC实现由显式本机项目配置装配；接口本身不授予目录访问或执行权限。</p>
 */
public interface LocalProjectRepository {
    /**
     * @param tenantId 可信租户
     * @param ownerId 已认证主体，必须精确比较
     * @param projectId 项目标识
     * @return 不可见或不存在均为空，不向越权主体泄露存在性
     */
    Optional<LocalProject> find(UUID tenantId, String ownerId, UUID projectId);

    /**
     * 在调用方元数据短事务内取得项目行锁，锁顺序为项目再会话/Run；不得在锁内打开文件或等待模型。
     * @param tenantId 可信租户 @param ownerId 认证主体 @param projectId 项目标识
     * @return 当前精确owner项目，旧嵌入实现仅提供可见性核验
     */
    default Optional<LocalProject> lock(UUID tenantId, String ownerId, UUID projectId) { return find(tenantId, ownerId, projectId); }

    /**
     * 按updatedAt/id倒序分页，必须在LIMIT前完成tenant/owner/归档过滤。
     * @param tenantId 可信租户
     * @param ownerId 已认证主体
     * @param page 完整复合游标
     * @param includeArchived 是否包含归档登记；不改变文件访问权限
     * @return 有界项目元数据，不含宿主路径
     */
    List<LocalProject> list(UUID tenantId, String ownerId, LocalProjectPageRequest page, boolean includeArchived);

    /**
     * 只新增初始ACTIVE/version=1项目，不允许覆盖已有标识或根。
     * @param project 由受信原生票据形成的元数据
     * @return 已存在时false；基础设施异常继续抛出，不伪装冲突或成功
     */
    boolean insert(LocalProject project);

    /**
     * 条件重命名，仅更新展示名/版本/时间，不修改owner、根身份或节点。
     * @param tenantId 可信租户
     * @param ownerId 已认证主体
     * @param projectId 项目标识
     * @param expectedVersion 已读取的版本
     * @param name 已验证的新展示名
     * @param now 可信服务端时间
     * @return 归属/状态/版本条件不满足均false，不自动重试
     */
    boolean rename(UUID tenantId, String ownerId, UUID projectId, long expectedVersion, String name, Instant now);

    /**
     * 条件归档只移除活动登记，不删除项目根或历史；运行收口由上层先完成。
     * @param tenantId 可信租户
     * @param ownerId 已认证主体
     * @param projectId 项目标识
     * @param expectedVersion 已读取版本
     * @param now 可信服务端时间
     * @return 条件更新是否获胜，不进行本地文件操作
     */
    boolean archive(UUID tenantId, String ownerId, UUID projectId, long expectedVersion, Instant now);
}

