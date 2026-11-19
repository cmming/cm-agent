package com.cmagent.agentscope;

import io.agentscope.core.state.State;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 单轮模型发送授权的加密检查点。只有运行宿主可以在确认待审批项的主体和摘要后显示预览。
 *
 * @param authorized 已批准的目标和片段摘要，不携带片段明文
 * @param pending 当前待审批的预览和绑定信息，终态清理全部运行检查点
 */
public record ProjectModelDisclosureState(Set<String> authorized, Proposal pending) implements State {
    /** 同一个 Run 内唯一的模型发送授权状态槽。 */
    public static final String KEY = "project_model_disclosure";
    /** 仅在本机审批执行循环使用的名字，不随工具目录发给模型。 */
    public static final String NAME = "project_model_disclosure";
    /**
     * 为 JDBC 审批外键生成租户内稳定的私有类型标识。宿主仅注册禁用的定义，不授予工具权限。
     *
     * @param tenantId 可信 Run 所属租户
     * @return 仅用于本租户模型发送审批的标识
     */
    public static UUID approvalTypeId(UUID tenantId) {
        String name = "cm-agent/project-model-disclosure/v1/" + Objects.requireNonNull(tenantId, "租户不能为空");
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    public ProjectModelDisclosureState {
        authorized = Set.copyOf(authorized);
    }

    /**
     * @param callId 宿主为本次提交生成的唯一调用标识
     * @param digest 绑定本轮、模型目标和片段摘要的标识，随本地审批调用一起保存
     * @param inputHash 审批输入规范哈希，用于将预览关联到同一次持久化审批
     * @param hashes 当前预览中的目标与片段摘要
     * @param preview 可向请求所有者显示的脱敏内容，与本轮实际发送的片段一致
     */
    public record Proposal(String callId, String digest, String inputHash, Set<String> hashes, Preview preview) {
        public Proposal {
            hashes = Set.copyOf(hashes);
        }
    }

    /**
     * @param targetModel 实际配置的 Provider 与模型显示名，不包含内部地址或凭据
     * @param fragments 首次发往这个目标前需要确认的脱敏片段
     */
    public record Preview(String targetModel, List<String> fragments) {
        public Preview {
            fragments = List.copyOf(fragments);
        }
    }
}
