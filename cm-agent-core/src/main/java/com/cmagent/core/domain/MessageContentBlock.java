package com.cmagent.core.domain;

import java.util.Objects;

/**
 * 一条消息中的有序、类型化内容块。
 *
 * <p>工具块只保存可展示的受限载荷；运行编排服务必须在事件或快照离开服务端边界前完成脱敏和限长，
 * 不能将模型原始参数、工具原始响应或凭据持久化或下发到浏览器。各类型允许的字段组合见紧凑构造器校验。</p>
 *
 * @param type 内容块类型，决定哪些字段必须或禁止出现
 * @param text 文本内容；TEXT 与 THINKING 块必须非空，工具块中存放待服务端脱敏的可展示快照
 * @param toolCallId 工具调用标识；TOOL_USE 与 TOOL_RESULT 块必填
 * @param toolName 工具名称；仅 TOOL_USE 块携带
 * @param status 调用终态；仅 TOOL_RESULT 块携带
 * @param durationMillis 调用耗时；仅 TOOL_RESULT 块可选且必须非负，旧消息缺失时为空
 */
public record MessageContentBlock(
        MessageContentType type,
        String text,
        String toolCallId,
        String toolName,
        RunStatus status,
        Long durationMillis
) {
    public MessageContentBlock {
        Objects.requireNonNull(type, "type 不能为空");
        text = text == null ? "" : text;
        toolCallId = blankToNull(toolCallId);
        toolName = blankToNull(toolName);
        switch (type) {
            case TEXT, THINKING -> {
                if (text.isBlank() || toolCallId != null || toolName != null || status != null || durationMillis != null) {
                    throw new IllegalArgumentException(type + " 内容块只能包含非空文本");
                }
            }
            case TOOL_USE -> {
                if (toolCallId == null || toolName == null || status != null || durationMillis != null) {
                    throw new IllegalArgumentException("TOOL_USE 必须包含调用标识和工具名称");
                }
            }
            case TOOL_RESULT -> {
                if (toolCallId == null || status == null || (durationMillis != null && durationMillis < 0)) {
                    throw new IllegalArgumentException("TOOL_RESULT 必须包含调用标识和终态");
                }
            }
        }
    }

    /**
     * 创建普通文本块。
     *
     * @param text 已按调用边界脱敏的非空文本
     * @return 不携带工具关联信息的文本块
     */
    public static MessageContentBlock text(String text) {
        return new MessageContentBlock(MessageContentType.TEXT, text, null, null, null, null);
    }

    /**
     * 创建模型实际返回的思考块。
     *
     * <p>调用方必须先完成脱敏；该内容仅用于可观察性展示，不会被
     * {@link ConversationMessage#textContent()} 投影为下一轮提示词历史。</p>
     *
     * @param thinking 已脱敏的非空思考文本
     * @return 不携带工具关联信息的思考块
     */
    public static MessageContentBlock thinking(String thinking) {
        return new MessageContentBlock(MessageContentType.THINKING, thinking, null, null, null, null);
    }

    /**
     * 创建工具调用摘要块；{@code inputSummary} 在持久化或下发前必须由运行编排服务完成脱敏。
     *
     * @param toolCallId 本次运行内的稳定工具调用标识
     * @param toolName 工具名称快照
     * @param inputSummary 已限长、尚待服务端脱敏的输入快照
     * @return 工具调用内容块
     */
    public static MessageContentBlock toolUse(String toolCallId, String toolName, String inputSummary) {
        return new MessageContentBlock(MessageContentType.TOOL_USE, inputSummary, toolCallId, toolName, null, null);
    }

    /**
     * 创建工具结果摘要块；运行编排服务不得持久化或下发未经脱敏的工具原始响应或异常细节。
     *
     * @param toolCallId 与 {@code TOOL_USE} 块对应的稳定标识
     * @param status 工具调用终态
     * @param resultSummary 已限长、尚待服务端脱敏的输出或错误快照
     * @return 不包含耗时的工具结果内容块，兼容既有调用方
     */
    public static MessageContentBlock toolResult(String toolCallId, RunStatus status, String resultSummary) {
        return toolResult(toolCallId, status, resultSummary, null);
    }

    /**
     * 创建包含真实调用耗时的工具结果块。
     *
     * <p>耗时来自服务端工具桥接器的单调时钟，不由浏览器推算；输入、输出或错误摘要仍必须在运行编排边界
     * 完成脱敏后才可持久化。</p>
     *
     * @param toolCallId 与 {@code TOOL_USE} 块对应的稳定标识
     * @param status 工具调用终态
     * @param resultSummary 已限长、尚待服务端脱敏的输出或错误快照
     * @param durationMillis 可选的非负服务端调用耗时（毫秒）
     * @return 工具结果内容块
     */
    public static MessageContentBlock toolResult(
            String toolCallId,
            RunStatus status,
            String resultSummary,
            Long durationMillis
    ) {
        return new MessageContentBlock(
                MessageContentType.TOOL_RESULT, resultSummary, toolCallId, null, status, durationMillis);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
