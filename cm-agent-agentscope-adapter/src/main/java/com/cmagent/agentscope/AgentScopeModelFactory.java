package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentDefinition;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;

import java.util.Objects;

/** 创建 OpenAI 兼容模型；凭据只从构造参数或环境变量读取。 */
public class AgentScopeModelFactory implements java.util.function.Function<AgentDefinition, Model> {
    private final String apiKey;
    private final String baseUrl;

    public AgentScopeModelFactory() {
        this(System.getenv("CM_AGENT_MODEL_API_KEY"), System.getenv("CM_AGENT_MODEL_BASE_URL"));
    }

    public AgentScopeModelFactory(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public Model apply(AgentDefinition agent) {
        Objects.requireNonNull(apiKey, "模型 API Key 未配置");
        Objects.requireNonNull(baseUrl, "模型 Base URL 未配置");
        GenerateOptions options = GenerateOptions.builder().apiKey(apiKey).baseUrl(baseUrl)
                .modelName(agent.modelName()).temperature(agent.temperature()).stream(false).build();
        return OpenAIChatModel.builder().apiKey(apiKey).baseUrl(baseUrl).modelName(agent.modelName())
                .stream(false).generateOptions(options).build();
    }
}
