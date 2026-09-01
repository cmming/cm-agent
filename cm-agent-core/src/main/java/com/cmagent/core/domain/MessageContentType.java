package com.cmagent.core.domain;

/** 允许进入持久化会话历史的受控内容块类型。 */
public enum MessageContentType {
    /** 普通文本块；USER 与 SYSTEM 消息只允许该类型。 */
    TEXT,

    /** 模型实际返回的思考块；只允许 ASSISTANT 消息持有，且不参与历史提示词文本投影。 */
    THINKING,

    /** 工具调用摘要块，保存脱敏后的输入摘要而非模型原始参数。 */
    TOOL_USE,

    /** 工具结果摘要块，保存脱敏后的输出或错误摘要及调用终态。 */
    TOOL_RESULT
}
