package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.runtime.ModelCredential;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.util.Objects;

/**
 * 将 CM Agent 的模型元数据与外部凭据适配为 AgentScope {@link Model}。
 *
 * <p>工厂只负责 Provider 类型和生成参数映射，不缓存模型或凭据。调用方为每次运行创建模型，
 * 因而模型配置变更和密钥轮换可在下一次运行生效，也不会在不同租户之间共享带凭据的实例。</p>
 */
public class AgentScopeModelFactory {

    /**
     * 创建与领域 Provider 类型匹配、启用事件流输出的 AgentScope 聊天模型。
     *
     * <p>Agent 自身配置的模型名优先于模型配置的默认值；温度统一映射为
     * {@link GenerateOptions}。两种 Provider 的 Builder 使用不同的默认选项入口，
     * 因此这里分别调用 {@code generateOptions} 和 {@code defaultOptions}。</p>
     *
     * <p>{@code stream(true)} 使 {@link AgentScopeReActExecutor} 能够通过事件流接收模型文本增量，
     * 不是对外 HTTP 接口是否采用流式响应的开关。</p>
     *
     * @param config 当前租户已校验的模型配置
     * @param agent 本次运行的 Agent 定义
     * @param credential 与当前租户及模型配置匹配的受控凭据
     * @return 新创建的 AgentScope 模型实例
     */
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
