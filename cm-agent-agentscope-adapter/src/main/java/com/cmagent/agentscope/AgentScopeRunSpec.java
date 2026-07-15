package com.cmagent.agentscope;

public record AgentScopeRunSpec(String tenantId, String agentId, String principalId, String userInput,
                                String systemPrompt, String modelName, double temperature, int maxIterations) {
}
