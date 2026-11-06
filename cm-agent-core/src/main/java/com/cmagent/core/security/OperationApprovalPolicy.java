package com.cmagent.core.security;

import com.cmagent.core.domain.ToolRiskLevel;
import java.util.Objects;

/**
 * 根据可信操作类别和本轮方式决定是否需要逐次确认。
 *
 * <p>此纯策略不负责认证、设备配对、租户隔离、工具授权、审计或并发控制。调用方必须先依据
 * 服务端权威状态完成这些检查，并在实际派发前再次核验；不能相信客户端或模型传入的类别、
 * 风险、授权标志和审批能力。该类没有可变状态，可以在线程间共享。</p>
 */
public final class OperationApprovalPolicy {
    private OperationApprovalPolicy() { }

    /**
     * 为已经识别的当前调用计算确认结果。
     *
     * <p>文件变更可能覆盖或删除用户数据，命令可能自行读写文件、联网或启动子进程，因此两者在
     * 风险询问方式下至少按高风险处理。
     * 无法识别的操作在完全访问方式下不能自动放行；其他方式只有存在审批入口时才可询问。
     * 此结果不构成一次可消费的批准事实，调用方仍须持久化相应决定并执行完整治理。</p>
     *
     * @param mode 服务端认可的本轮方式，不能为 {@code null}
     * @param category 服务端可信目录确定的类别，不能为 {@code null}
     * @param riskLevel 服务端核验的风险等级；未知时为 {@code null}
     * @param authorized 认证、归属、工具及本轮授权均已通过时为 {@code true}
     * @param mandatoryApproval 部署策略要求当前调用必须人工确认时为 {@code true}
     * @param approvalAvailable 当前调用存在可恢复的人工审批入口时为 {@code true}
     * @return 本次调用的允许、询问或拒绝决定
     */
    public static OperationApprovalDecision decide(
            OperationAuthorizationMode mode,
            OperationCategory category,
            ToolRiskLevel riskLevel,
            boolean authorized,
            boolean mandatoryApproval,
            boolean approvalAvailable
    ) {
        Objects.requireNonNull(mode, "授权方式不能为空");
        Objects.requireNonNull(category, "操作类别不能为空");

        // 身份和工具授权由调用方从可信状态得到；人工批准不能补足不存在的权限。
        if (!authorized) {
            return OperationApprovalDecision.DENY;
        }
        if (category == OperationCategory.UNKNOWN || riskLevel == null) {
            return mode == OperationAuthorizationMode.FULL_ACCESS
                    ? OperationApprovalDecision.DENY : askOrDeny(approvalAvailable);
        }
        if (mandatoryApproval) {
            return askOrDeny(approvalAvailable);
        }
        boolean highRisk = riskLevel == ToolRiskLevel.HIGH
                || category == OperationCategory.FILE_CHANGE || category == OperationCategory.COMMAND;
        return switch (mode) {
            case ASK_ON_EFFECT -> category != OperationCategory.READ_ONLY || highRisk
                    ? askOrDeny(approvalAvailable) : OperationApprovalDecision.ALLOW;
            case ASK_ON_RISK -> highRisk ? askOrDeny(approvalAvailable) : OperationApprovalDecision.ALLOW;
            case FULL_ACCESS -> OperationApprovalDecision.ALLOW;
        };
    }

    private static OperationApprovalDecision askOrDeny(boolean approvalAvailable) {
        // 无法暂停并恢复的入口不能留下无人处理的待审批调用。
        return approvalAvailable ? OperationApprovalDecision.ASK : OperationApprovalDecision.DENY;
    }
}
