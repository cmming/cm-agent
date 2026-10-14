package com.cmagent.agentscope;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolApprovalDecision;
import com.cmagent.core.runtime.RuntimeApprovalDecisions;
import com.cmagent.core.runtime.RuntimeExecutionModeConflictException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeExecutionModeConflictTest {
    @ParameterizedTest
    @ValueSource(strings = {"run", "structured", "resume"})
    void 模式冲突所有入口均在模型工具及事件前拒绝并关联脱敏日志(String entry) {
        var logger = (Logger) LoggerFactory.getLogger(AgentScopeRuntimeAdapter.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            var runtime = new AgentScopeRuntimeAdapter((tenant, model) -> { throw new RuntimeExecutionModeConflictException(); },
                    tool -> { throw new AssertionError("不得调用工具"); },
                    (spec, credential, gateway) -> { throw new AssertionError("不得创建模型执行器"); }, Clock.systemUTC());
            var request = AgentScopeSkillSessionTest.request();
            var decisions = new RuntimeApprovalDecisions(List.of(new RuntimeApprovalDecisions.Item(
                    "fixture-call", UUID.randomUUID(), "a".repeat(64), ToolApprovalDecision.APPROVE)));
            var result = switch (entry) {
                case "structured" -> runtime.runStructured(request, delta -> { throw new AssertionError("不得发出文本"); },
                        progress -> { throw new AssertionError("不得发出事件"); }).run();
                case "resume" -> runtime.resumeStructured(request, decisions,
                        delta -> { throw new AssertionError("不得发出文本"); },
                        progress -> { throw new AssertionError("不得发出事件"); }).run();
                default -> runtime.run(request);
            };
            assertThat(result.status()).isEqualTo(RunStatus.DENIED);
            assertThat(result.errorMessage()).isEqualTo("执行模式已切换，请排空旧实例并重启服务");
            assertThat(result.toolCalls()).isEmpty();
            assertThat(logs.list).anySatisfy(log -> {
                assertThat(log.getFormattedMessage()).contains("errorId=" + request.runId(),
                        "errorCode=RUNTIME_MODE_CONFLICT", "tenantId=" + request.tenantId(),
                        "resourceType=run", "resourceId=" + request.runId());
                assertThat(log.getThrowableProxy()).isNull();
            });
            assertThat(result.errorMessage()).doesNotContain("https://", "Exception", request.input());
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
}
