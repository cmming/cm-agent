package com.cmagent.core.domain;

import com.cmagent.core.security.OperationAuthorizationMode;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 宿主为项目Run固定的可信工具上下文，不包含路径、密钥或文件正文。
 *
 * <p>只能由已经核验的项目绑定和服务端登记工具形成；不能接收模型/HTTP自报的工具名或集合。
 * 所有成员仍须经过当前工具grant、项目owner和风险审批复核，本上下文本身不授予权限。</p>
 * @param binding Server 本机项目来源；客户端项目为空
 * @param clientBinding 已配对设备项目来源；Server 本机项目为空
 * @param protectedToolIds 服务端本次允许的项目工具精确标识，决定输入输出摘要保护
 * @param authorizationMode 本轮项目文件与命令使用的授权方式
 */
public record ProjectRuntimeContext(LocalProjectBinding binding, ClientProjectBinding clientBinding,
                                    Set<UUID> protectedToolIds,
                                    OperationAuthorizationMode authorizationMode) {
    /** 兼容旧调用；未显式提交模式时保持原有的高风险逐次确认行为。 */
    public ProjectRuntimeContext(LocalProjectBinding binding, Set<UUID> protectedToolIds) {
        this(binding, null, protectedToolIds, OperationAuthorizationMode.ASK_ON_RISK);
    }

    /** 保留 Server 本机项目显式授权方式构造。 */
    public ProjectRuntimeContext(LocalProjectBinding binding, Set<UUID> protectedToolIds,
            OperationAuthorizationMode authorizationMode) {
        this(binding, null, protectedToolIds, authorizationMode);
    }

    /** 创建已配对设备项目上下文。 */
    public ProjectRuntimeContext(ClientProjectBinding clientBinding, Set<UUID> protectedToolIds,
            OperationAuthorizationMode authorizationMode) {
        this(null, clientBinding, protectedToolIds, authorizationMode);
    }

    public ProjectRuntimeContext {
        if ((binding == null) == (clientBinding == null)) {
            throw new IllegalArgumentException("项目运行上下文必须且只能绑定一种来源");
        }
        // record 紧凑构造器结束前字段尚未赋值，使用参数而非访问器计算共同来源。
        ProjectBinding fixed = binding != null ? binding : clientBinding;
        if (fixed.runId() == null) throw new IllegalArgumentException("运行上下文必须来自固定Run绑定");
        protectedToolIds = Set.copyOf(Objects.requireNonNull(protectedToolIds));
        Objects.requireNonNull(authorizationMode, "项目授权方式不能为空");
    }

    /** @return 两种来源的共同不可变身份 */
    public ProjectBinding sourceBinding() {
        return binding == null ? clientBinding : binding;
    }
}

