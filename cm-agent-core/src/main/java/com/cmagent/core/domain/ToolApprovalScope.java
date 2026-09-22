package com.cmagent.core.domain;

/** 标识工具审批依附于正式会话还是独立试运行。 */
public enum ToolApprovalScope {
    /** 审批属于持久化会话，必须携带会话标识。 */
    CONVERSATION,
    /** 审批属于无会话的 TEST Run，以运行标识作为恢复边界。 */
    RUN
}
