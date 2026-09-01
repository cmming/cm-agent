package com.cmagent.core.domain;

/** Agent 运行期间允许越过 Runtime 边界的受控执行进度类型。 */
public enum AgentProgressEventType {
    /** 模型开始生成一段思考块；事件不携带尚未完成的思考文本。 */
    THINKING_STARTED,

    /** 一段模型思考块已经完整生成，可以在上层完成整体脱敏后展示。 */
    THINKING_COMPLETED,

    /** 模型开始准备一次工具调用，只公开受治理工具名称和调用标识。 */
    TOOL_CALL_STARTED,

    /** 模型已完成工具参数生成，但原始参数不会进入该事件。 */
    TOOL_CALL_COMPLETED,

    /** 受治理工具开始执行，表示调用已进入 AgentScope 工具执行阶段。 */
    TOOL_EXECUTION_STARTED,

    /** 受治理工具执行结束，并携带映射后的领域终态。 */
    TOOL_EXECUTION_COMPLETED
}
