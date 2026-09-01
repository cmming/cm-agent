package com.cmagent.core.domain;

import java.util.Objects;

/**
 * Agent 运行期间发送给会话观察者的受控执行进度。
 *
 * <p>该对象只表达模型实际产生的思考块以及工具生命周期。工具事件禁止携带参数和结果载荷；
 * {@code content} 仅允许完整的思考块使用，并且仍需在服务端运行编排边界统一脱敏。</p>
 *
 * @param type 进度事件类型，决定其余字段的允许组合
 * @param replyId AgentScope 回复标识；框架未提供时可为空
 * @param blockId 思考块标识；仅思考事件使用，框架未提供时可为空
 * @param toolCallId 工具调用标识；工具事件必填
 * @param toolName 已授权工具的名称快照；工具事件必填
 * @param content 完整思考文本；仅 {@link AgentProgressEventType#THINKING_COMPLETED} 必填
 * @param status 工具执行终态；仅 {@link AgentProgressEventType#TOOL_EXECUTION_COMPLETED} 必填
 */
public record AgentProgressEvent(
        AgentProgressEventType type,
        String replyId,
        String blockId,
        String toolCallId,
        String toolName,
        String content,
        RunStatus status
) {
    public AgentProgressEvent {
        Objects.requireNonNull(type, "type 不能为空");
        replyId = blankToNull(replyId);
        blockId = blankToNull(blockId);
        toolCallId = blankToNull(toolCallId);
        toolName = blankToNull(toolName);
        content = blankToNull(content);
        switch (type) {
            case THINKING_STARTED -> requireThinkingFields(content == null, toolCallId, toolName, status);
            case THINKING_COMPLETED -> requireThinkingFields(content != null, toolCallId, toolName, status);
            case TOOL_CALL_STARTED, TOOL_CALL_COMPLETED, TOOL_EXECUTION_STARTED ->
                    requireToolFields(toolCallId, toolName, content, status == null);
            case TOOL_EXECUTION_COMPLETED -> requireToolFields(toolCallId, toolName, content, status != null);
        }
    }

    /**
     * 创建不携带未完成内容的思考开始事件。
     *
     * @param replyId 可选的回复标识
     * @param blockId 可选的思考块标识
     * @return 不含思考文本的开始事件
     */
    public static AgentProgressEvent thinkingStarted(String replyId, String blockId) {
        return new AgentProgressEvent(
                AgentProgressEventType.THINKING_STARTED, replyId, blockId, null, null, null, null);
    }

    /**
     * 创建携带完整思考块的思考完成事件。
     *
     * @param replyId 可选的回复标识
     * @param blockId 可选的思考块标识
     * @param content 完整且尚待服务端纵深脱敏的思考文本
     * @return 思考完成事件
     */
    public static AgentProgressEvent thinkingCompleted(String replyId, String blockId, String content) {
        return new AgentProgressEvent(
                AgentProgressEventType.THINKING_COMPLETED, replyId, blockId, null, null, content, null);
    }

    /**
     * 创建工具调用开始事件。
     *
     * @param replyId 可选的回复标识
     * @param toolCallId 非空工具调用标识
     * @param toolName 已授权工具名称
     * @return 不含工具参数的调用开始事件
     */
    public static AgentProgressEvent toolCallStarted(String replyId, String toolCallId, String toolName) {
        return toolEvent(AgentProgressEventType.TOOL_CALL_STARTED, replyId, toolCallId, toolName, null);
    }

    /**
     * 创建工具参数准备完成事件，事件不会携带原始参数。
     *
     * @param replyId 可选的回复标识
     * @param toolCallId 非空工具调用标识
     * @param toolName 已授权工具名称
     * @return 不含工具参数的调用完成事件
     */
    public static AgentProgressEvent toolCallCompleted(String replyId, String toolCallId, String toolName) {
        return toolEvent(AgentProgressEventType.TOOL_CALL_COMPLETED, replyId, toolCallId, toolName, null);
    }

    /**
     * 创建工具执行开始事件。
     *
     * @param replyId 可选的回复标识
     * @param toolCallId 非空工具调用标识
     * @param toolName 已授权工具名称
     * @return 工具执行开始事件
     */
    public static AgentProgressEvent toolExecutionStarted(String replyId, String toolCallId, String toolName) {
        return toolEvent(AgentProgressEventType.TOOL_EXECUTION_STARTED, replyId, toolCallId, toolName, null);
    }

    /**
     * 创建工具执行结束事件。
     *
     * @param replyId 可选的回复标识
     * @param toolCallId 非空工具调用标识
     * @param toolName 已授权工具名称
     * @param status 映射后的非空工具执行终态
     * @return 不含工具原始结果的执行结束事件
     */
    public static AgentProgressEvent toolExecutionCompleted(
            String replyId, String toolCallId, String toolName, RunStatus status) {
        return toolEvent(AgentProgressEventType.TOOL_EXECUTION_COMPLETED, replyId, toolCallId, toolName, status);
    }

    private static AgentProgressEvent toolEvent(
            AgentProgressEventType type,
            String replyId,
            String toolCallId,
            String toolName,
            RunStatus status
    ) {
        return new AgentProgressEvent(type, replyId, null, toolCallId, toolName, null, status);
    }

    private static void requireThinkingFields(
            boolean validContent, String toolCallId, String toolName, RunStatus status) {
        if (!validContent || toolCallId != null || toolName != null || status != null) {
            throw new IllegalArgumentException("思考事件字段组合不合法");
        }
    }

    private static void requireToolFields(
            String toolCallId, String toolName, String content, boolean validStatus) {
        if (toolCallId == null || toolName == null || content != null || !validStatus) {
            throw new IllegalArgumentException("工具进度事件字段组合不合法");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
