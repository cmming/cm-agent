package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.runtime.ModelCredential;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.util.Objects;

/** 根据模型配置和凭据创建 AgentScope 模型实例。 */
public class AgentScopeModelFactory {

    /** 创建与 Provider 类型匹配的 AgentScope 聊天模型。 */
    public Model create(ModelConfig config, AgentDefinition agent, ModelCredential credential) {
        Objects.requireNonNull(config, "config 不能为空");
        Objects.requireNonNull(agent, "agent 不能为空");
        Objects.requireNonNull(credential, "credential 不能为空");

        String modelName = agent.modelName() == null || agent.modelName().isBlank()
                ? config.modelName()
                : agent.modelName();
        GenerateOptions options = GenerateOptions.builder()
                .temperature(agent.temperature())
                .build();

        return switch (config.providerType()) {
            case OPENAI_COMPATIBLE -> OpenAIChatModel.builder()
                    .apiKey(credential.apiKey())
                    .baseUrl(config.baseUrl())
                    .modelName(modelName)
                    .stream(true)
                    .generateOptions(options)
                    .build();
            case DASHSCOPE_NATIVE -> DashScopeChatModel.builder()
                    .apiKey(credential.apiKey())
                    .baseUrl(config.baseUrl())
                    .modelName(modelName)
                    .stream(true)
                    .defaultOptions(options)
                    .build();
        };
    }
}
