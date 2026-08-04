package com.cmagent.core.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ToolInvocationInfrastructureExceptionTest {

    @Test
    /**
     * 验证或支持 {@code retainsControlledMessageAndCause} 所描述的测试场景。
     */
    void retainsControlledMessageAndCause() {
        IllegalStateException cause = new IllegalStateException("数据库不可用");

        ToolInvocationInfrastructureException exception =
                new ToolInvocationInfrastructureException("工具调用基础设施失败", cause);

        assertThat(exception).hasMessage("工具调用基础设施失败").hasCause(cause);
    }

    @Test
    /**
     * 验证 {@code BlankMessage} 异常场景会被正确拒绝。
     */
    void rejectsBlankMessage() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ToolInvocationInfrastructureException("  ", new IllegalStateException()))
                .withMessage("工具调用基础设施失败消息不能为空");
    }
}
