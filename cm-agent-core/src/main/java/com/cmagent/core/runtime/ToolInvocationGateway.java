package com.cmagent.core.runtime;

@FunctionalInterface
public interface ToolInvocationGateway {

    ToolInvocationResult invoke(ToolInvocationRequest request);
}
