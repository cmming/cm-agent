package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.ToolCallRecord;
import com.cmagent.core.runtime.ModelCredential;
import com.cmagent.core.runtime.ToolInvocationGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 为服务端集成测试提供真实 Adapter 与受控执行器，不扩大生产 API 的可见范围。
 */
@TestConfiguration(proxyBeanMethods = false)
public class ControlledAgentScopeRuntimeTestConfiguration {

    @Bean
    /**
     * 验证或支持 {@code controlledAgentScopeExecutor} 所描述的测试场景。
     */
    ControlledExecutor controlledAgentScopeExecutor() {
        return new ControlledExecutor();
    }

    @Bean
    @Primary
    /**
     * 验证或支持 {@code controlledAgentScopeRuntime} 所描述的测试场景。
     *
     * @param gateway 测试工具调用网关
     * @param executor 测试执行器
     */
    AgentScopeRuntimeAdapter controlledAgentScopeRuntime(
            ToolInvocationGateway gateway,
            ControlledExecutor executor
    ) {
        return new AgentScopeRuntimeAdapter(
                (tenantId, modelConfigId) -> new ModelCredential("unit-test-jdbc-key"),
                gateway,
                executor,
                Clock.systemUTC());
    }

    public static final class ControlledExecutor implements AgentScopeExecutor {
        private final AtomicReference<AgentRunRequest> lastRequest = new AtomicReference<>();
        private final AtomicReference<List<ToolCallRecord>> nextToolCalls = new AtomicReference<>(List.of());
        private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();
        private final AtomicInteger invocationCount = new AtomicInteger();

        @Override
        /**
         * 验证或支持 {@code execute} 所描述的测试场景。
         *
         * @param spec 测试辅助方法使用的 spec 参数
         * @param credential 测试模型凭据
         * @param toolGateway 测试辅助方法使用的 toolGateway 参数
         */
        public AgentScopeExecutionResult execute(
                AgentScopeRunSpec spec,
                ModelCredential credential,
                ToolInvocationGateway toolGateway
        ) {
            invocationCount.incrementAndGet();
            lastRequest.set(spec.request());
            RuntimeException failure = nextFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            return AgentScopeExecutionResult.succeeded(
                    "fake-runtime: " + spec.userInput(), nextToolCalls.getAndSet(List.of()));
        }

        /**
         * 验证或支持 {@code reset} 所描述的测试场景。
         */
        public void reset() {
            lastRequest.set(null);
            nextToolCalls.set(List.of());
            nextFailure.set(null);
            invocationCount.set(0);
        }

        /**
         * 验证或支持 {@code returnToolCalls} 所描述的测试场景。
         *
         * @param toolCalls 测试辅助方法使用的 toolCalls 参数
         */
        public void returnToolCalls(List<ToolCallRecord> toolCalls) {
            nextToolCalls.set(List.copyOf(toolCalls));
        }

        /**
         * 验证或支持 {@code failNext} 所描述的测试场景。
         *
         * @param failure 测试构造的失败
         */
        public void failNext(RuntimeException failure) {
            nextFailure.set(failure);
        }

        /**
         * 验证或支持 {@code lastRequest} 所描述的测试场景。
         */
        public AgentRunRequest lastRequest() {
            return lastRequest.get();
        }

        /**
         * 验证或支持 {@code invocationCount} 所描述的测试场景。
         */
        public int invocationCount() {
            return invocationCount.get();
        }
    }
}
