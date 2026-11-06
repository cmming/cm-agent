package com.cmagent.core.security;

import com.cmagent.core.domain.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import static com.cmagent.core.security.OperationApprovalDecision.*;
import static com.cmagent.core.security.OperationAuthorizationMode.*;
import static com.cmagent.core.security.OperationCategory.*;
import static org.assertj.core.api.Assertions.assertThat;

class OperationApprovalPolicyTest {
    @Test
    void 操作前询问允许已识别低风险读取但询问外部副作用() {
        assertThat(decide(ASK_ON_EFFECT, READ_ONLY, ToolRiskLevel.LOW)).isEqualTo(ALLOW);
        assertThat(decide(ASK_ON_EFFECT, READ_ONLY, ToolRiskLevel.HIGH)).isEqualTo(ASK);
        assertThat(decide(ASK_ON_EFFECT, FILE_CHANGE, ToolRiskLevel.LOW)).isEqualTo(ASK);
        assertThat(decide(ASK_ON_EFFECT, NETWORK, ToolRiskLevel.LOW)).isEqualTo(ASK);
        assertThat(decide(ASK_ON_EFFECT, COMMAND, ToolRiskLevel.LOW)).isEqualTo(ASK);
    }

    @Test
    void 风险询问允许可信低风险但命令和高风险始终询问() {
        assertThat(decide(ASK_ON_RISK, READ_ONLY, ToolRiskLevel.LOW)).isEqualTo(ALLOW);
        assertThat(decide(ASK_ON_RISK, NETWORK, ToolRiskLevel.MEDIUM)).isEqualTo(ALLOW);
        assertThat(decide(ASK_ON_RISK, FILE_CHANGE, ToolRiskLevel.HIGH)).isEqualTo(ASK);
        assertThat(decide(ASK_ON_RISK, FILE_CHANGE, ToolRiskLevel.LOW)).isEqualTo(ASK);
        assertThat(decide(ASK_ON_RISK, COMMAND, ToolRiskLevel.LOW)).isEqualTo(ASK);
    }

    @Test
    void 完全访问允许已识别能力但不能自动执行未知类别或风险() {
        assertThat(decide(FULL_ACCESS, FILE_CHANGE, ToolRiskLevel.HIGH)).isEqualTo(ALLOW);
        assertThat(decide(FULL_ACCESS, NETWORK, ToolRiskLevel.HIGH)).isEqualTo(ALLOW);
        assertThat(decide(FULL_ACCESS, COMMAND, ToolRiskLevel.HIGH)).isEqualTo(ALLOW);
        assertThat(decide(FULL_ACCESS, UNKNOWN, ToolRiskLevel.LOW)).isEqualTo(DENY);
        assertThat(decide(FULL_ACCESS, READ_ONLY, null)).isEqualTo(DENY);
        assertThat(decide(ASK_ON_EFFECT, UNKNOWN, ToolRiskLevel.LOW)).isEqualTo(ASK);
        assertThat(decide(ASK_ON_RISK, READ_ONLY, null)).isEqualTo(ASK);
    }

    @Test
    void 授权拒绝优先于所有模式且更严格策略不能被完全访问绕过() {
        for (var mode : OperationAuthorizationMode.values()) {
            assertThat(OperationApprovalPolicy.decide(mode, READ_ONLY, ToolRiskLevel.LOW,
                    false, false, true)).isEqualTo(DENY);
            assertThat(OperationApprovalPolicy.decide(mode, READ_ONLY, ToolRiskLevel.LOW,
                    true, true, true)).isEqualTo(ASK);
        }
    }

    @Test
    void 无审批恢复入口时需要询问的调用直接拒绝() {
        assertThat(OperationApprovalPolicy.decide(ASK_ON_EFFECT, NETWORK, ToolRiskLevel.LOW,
                true, false, false)).isEqualTo(DENY);
        assertThat(OperationApprovalPolicy.decide(ASK_ON_RISK, UNKNOWN, null,
                true, false, false)).isEqualTo(DENY);
        assertThat(OperationApprovalPolicy.decide(FULL_ACCESS, COMMAND, ToolRiskLevel.HIGH,
                true, true, false)).isEqualTo(DENY);
    }

    private static OperationApprovalDecision decide(
            OperationAuthorizationMode mode, OperationCategory category, ToolRiskLevel risk) {
        return OperationApprovalPolicy.decide(mode, category, risk, true, false, true);
    }
}
