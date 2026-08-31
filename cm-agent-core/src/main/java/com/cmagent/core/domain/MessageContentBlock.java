package com.cmagent.core.domain;

import java.util.Objects;

/**
 * 一条消息中的有序、类型化内容块。
 *
 * <p>工具块只保存受治理后的摘要，不能放入模型原始参数、工具原始响应或凭据。</p>
 */
public record MessageContentBlock(
        MessageContentType type,
        String text,
        String toolCallId,
        String toolName,
        RunStatus status
) {
    public MessageContentBlock {
        Objects.requireNonNull(type, "type 不能为空");
        text = text == null ? "" : text;
        toolCallId = blankToNull(toolCallId);
        toolName = blankToNull(toolName);
        switch (type) {
            case TEXT -> {
                if (text.isBlank() || toolCallId != null || toolName != null || status != null) {
                    throw new IllegalArgumentException("TEXT 内容块只能包含非空文本");
                }
            }
            case TOOL_USE -> {
                if (toolCallId == null || toolName == null || status != null) {
                    throw new IllegalArgumentException("TOOL_USE 必须包含调用标识和工具名称");
                }
            }
            case TOOL_RESULT -> {
                if (toolCallId == null || status == null) {
                    throw new IllegalArgumentException("TOOL_RESULT 必须包含调用标识和终态");
                }
            }
        }
    }

    public static MessageContentBlock text(String text) {
        return new MessageContentBlock(MessageContentType.TEXT, text, null, null, null);
    }

    public static MessageContentBlock toolUse(String toolCallId, String toolName, String inputSummary) {
        return new MessageContentBlock(MessageContentType.TOOL_USE, inputSummary, toolCallId, toolName, null);
    }

    public static MessageContentBlock toolResult(String toolCallId, RunStatus status, String resultSummary) {
        return new MessageContentBlock(MessageContentType.TOOL_RESULT, resultSummary, toolCallId, null, status);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
