package com.cmagent.core.domain;

/** 枚举一次工具审批请求的生命周期状态。 */
public enum ToolApprovalStatus {
    /** 等待有审批权限的主体作出决定。 */
    PENDING,
    /** 所有明细均允许。 */
    APPROVED,
    /** 所有明细均拒绝。 */
    DENIED,
    /** 同一请求中同时存在允许和拒绝。 */
    PARTIALLY_APPROVED,
    /** 超过服务端有效期，不能再恢复执行。 */
    EXPIRED,
    /** Run 被取消或检查点失效后由服务端撤销。 */
    CANCELLED;

    /** @return 当前状态是否已经不可再次决定 */
    public boolean isTerminal() {
        return this != PENDING;
    }
}
