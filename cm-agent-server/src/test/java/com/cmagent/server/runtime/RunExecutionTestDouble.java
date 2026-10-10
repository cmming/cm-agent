package com.cmagent.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 审批测试保留真实短锁，仅替换 Runtime 编排；获取锁不等于恢复或执行模型。 */
final class RunExecutionTestDouble {
    private RunExecutionTestDouble() { }

    static RunExecutionService mockExecution() {
        RunControlRegistry controls = new RunControlRegistry();
        return mock(RunExecutionService.class, invocation -> "controls".equals(invocation.getMethod().getName())
                ? controls : RETURNS_DEFAULTS.answer(invocation));
    }

    static void assertNoExecution(RunExecutionService execution) {
        assertThat(mockingDetails(execution).getInvocations().stream()
                .filter(invocation -> !"controls".equals(invocation.getMethod().getName())).toList()).isEmpty();
    }
}
