package com.cmagent.core.runtime;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ToolInvocationResultTest {
    @Test void 旧构造器与工厂保留模型及展示同值语义() {
        var result = new ToolInvocationResult("旧输出", true, true, null);
        assertThat(result.output()).isEqualTo("旧输出");
        assertThat(result.displayOutput()).isEqualTo("旧输出");
        assertThat(result.protectedPayload()).isFalse();
        assertThat(result.errorMessage()).isEmpty();
        assertThat(ToolInvocationResult.succeeded("旧输出")).isEqualTo(result);
        assertThat(new ToolInvocationResult(null, false, false, null).displayOutput()).isEmpty();
    }

    @Test void 受保护结果分开正文和摘要且默认字符串不含载荷() {
        var result = ToolInvocationResult.succeededProtected("PRIVATE_FILE_BODY", "文件读取完成，1项");
        assertThat(result.output()).isEqualTo("PRIVATE_FILE_BODY");
        assertThat(result.displayOutput()).isEqualTo("文件读取完成，1项");
        assertThat(result.protectedPayload()).isTrue();
        assertThat(result.toString()).doesNotContain("PRIVATE_FILE_BODY", "文件读取完成");
    }

    @Test void 受保护失败和拒绝仍保持互斥状态与安全原因() {
        var failed = ToolInvocationResult.failedProtected("文件版本冲突，请重新预览");
        assertThat(failed.success()).isFalse();
        assertThat(failed.authorized()).isTrue();
        assertThat(failed.output()).isEmpty();
        assertThat(failed.errorMessage()).isEqualTo("文件版本冲突，请重新预览");
        assertThat(failed.protectedPayload()).isTrue();
        var denied = ToolInvocationResult.deniedProtected("无权读取项目");
        assertThat(denied.authorized()).isFalse();
        assertThat(denied.errorMessage()).isEqualTo("无权读取项目");
        assertThat(denied.toString()).doesNotContain("无权读取项目");
    }
}

