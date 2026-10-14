package com.cmagent.core.runtime;

/** 旧实例的执行模式已被部署流程切换；必须在模型及业务工具执行之前拒绝该轮运行。 */
public final class RuntimeExecutionModeConflictException extends RuntimeException {
    public RuntimeExecutionModeConflictException() {
        super("执行模式已切换，请排空旧实例并重启服务");
    }
}

