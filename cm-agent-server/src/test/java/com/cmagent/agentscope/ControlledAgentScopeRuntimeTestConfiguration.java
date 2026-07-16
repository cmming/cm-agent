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
    ControlledExecutor controlledAgentScopeExecutor() {
        return new ControlledExecutor();
    }

    @Bean
    @Primary
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

        public void reset() {
            lastRequest.set(null);
            nextToolCalls.set(List.of());
            nextFailure.set(null);
            invocationCount.set(0);
        }

        public void returnToolCalls(List<ToolCallRecord> toolCalls) {
            nextToolCalls.set(List.copyOf(toolCalls));
        }

        public void failNext(RuntimeException failure) {
            nextFailure.set(failure);
        }

        public AgentRunRequest lastRequest() {
            return lastRequest.get();
        }

        public int invocationCount() {
            return invocationCount.get();
        }
    }
}
