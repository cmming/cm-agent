package com.cmagent.agentscope;

/** 使用生产 Harness 工厂与真实包装层重跑相同权限、恢复、超时、并发、严格失败和停止合同。 */
class AgentScopeHarnessRuntimeContractTest extends AgentScopeRuntimeContractTest {
    @Override protected AgentScopeExecutionMode executionMode() { return AgentScopeExecutionMode.HARNESS; }
}
