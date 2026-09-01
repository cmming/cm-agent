package com.cmagent.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentProgressEventTest {

    @Test
    void 思考完成事件只接受完整思考文本() {
        AgentProgressEvent event = AgentProgressEvent.thinkingCompleted("reply-1", "thinking-1", "分析完成");

        assertThat(event.type()).isEqualTo(AgentProgressEventType.THINKING_COMPLETED);
        assertThat(event.content()).isEqualTo("分析完成");
        assertThat(event.toolCallId()).isNull();
    }

    @Test
    void 工具完成事件保留待服务端脱敏的返回值和耗时() {
        AgentProgressEvent event = AgentProgressEvent.toolExecutionCompleted(
                "reply-1", "call-1", "search", RunStatus.SUCCEEDED, "{\"total\":1}", 12L);

        assertThat(event.type()).isEqualTo(AgentProgressEventType.TOOL_EXECUTION_COMPLETED);
        assertThat(event.toolCallId()).isEqualTo("call-1");
        assertThat(event.toolName()).isEqualTo("search");
        assertThat(event.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(event.content()).isNull();
        assertThat(event.output()).isEqualTo("{\"total\":1}");
        assertThat(event.durationMillis()).isEqualTo(12L);
    }

    @Test
    void 工具调用开始事件可以携带待脱敏的入参快照() {
        AgentProgressEvent event = AgentProgressEvent.toolCallStarted(
                "reply-1", "call-1", "search", "{\"keyword\":\"客户\"}");

        assertThat(event.input()).isEqualTo("{\"keyword\":\"客户\"}");
        assertThat(event.output()).isNull();
    }

    @Test
    void 工具事件拒绝缺少调用标识() {
        assertThatThrownBy(() -> AgentProgressEvent.toolCallStarted("reply-1", "", "search"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("工具进度事件字段组合不合法");
    }
}
