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
 * @param binding 本Run不可变的项目/主体/根/节点来源
 * @param protectedToolIds 服务端本次允许的项目工具精确标识，决定输入输出摘要保护
 * @param authorizationMode 本轮项目文件与命令使用的授权方式
 */
public record ProjectRuntimeContext(LocalProjectBinding binding, Set<UUID> protectedToolIds,
                                    OperationAuthorizationMode authorizationMode) {
    /** 兼容旧调用；未显式提交模式时保持原有的高风险逐次确认行为。 */
    public ProjectRuntimeContext(LocalProjectBinding binding, Set<UUID> protectedToolIds) {
        this(binding, protectedToolIds, OperationAuthorizationMode.ASK_ON_RISK);
    }

    public ProjectRuntimeContext {
        Objects.requireNonNull(binding, "项目绑定不能为空");
        if (binding.runId() == null) throw new IllegalArgumentException("运行上下文必须来自固定Run绑定");
        protectedToolIds = Set.copyOf(Objects.requireNonNull(protectedToolIds));
        Objects.requireNonNull(authorizationMode, "项目授权方式不能为空");
    }
}

