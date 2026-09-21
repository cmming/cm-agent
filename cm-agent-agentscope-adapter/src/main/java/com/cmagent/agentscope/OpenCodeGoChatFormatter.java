package com.cmagent.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;

import java.util.List;

/**
 * 为 OpenCode Go 保留标准聊天格式，但省略端点不支持的消息发送者名称。
 *
 * <p>已核对 AgentScope 2.0.2 的 {@code OpenAIMessageConverter}：system、user 和 assistant
 * 消息会从 {@link Msg#getName()} 生成消息级 {@code name}，Go 端点会因此返回 HTTP 400。
 * 先委托框架创建新的传输 DTO，再清空该可选属性；框架 JSON 编码器会省略空属性。
 * 不修改原始消息、会话历史或工具的 {@code function.name}，工具匹配和治理身份保持不变。</p>
 *
 * <p>本类不持有可变运行状态；工厂仅在精确匹配 OpenCode Go 地址时装配。</p>
 */
final class OpenCodeGoChatFormatter extends OpenAIChatFormatter {

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        List<OpenAIMessage> messages = super.doFormat(msgs);
        messages.forEach(message -> message.setName(null));
        return messages;
    }
}
