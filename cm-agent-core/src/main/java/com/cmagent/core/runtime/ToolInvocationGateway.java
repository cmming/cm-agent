package com.cmagent.core.runtime;

@FunctionalInterface
/**
 * ToolInvocationGateway 的核心领域类型。
 */
public interface ToolInvocationGateway {

    /**
     * 定义 invoke 操作。
     */
    ToolInvocationResult invoke(ToolInvocationRequest request);
}
