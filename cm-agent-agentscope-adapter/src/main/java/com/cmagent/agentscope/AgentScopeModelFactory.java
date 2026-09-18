package com.cmagent.agentscope;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ModelConfig;
import com.cmagent.core.runtime.ModelCredential;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.util.Objects;
import java.util.UUID;

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
        return create(config, agent, credential, null);
    }

    /**
     * 创建与本次运行绑定的模型实例。
     *
     * <p>OpenCode Go 在 {@code opencode.ai} 域名下要求每个请求携带会话标识以便路由。
     * 运行标识来自服务端已创建的 {@code Run}，不是客户端可控值；同一运行中的模型重试会复用
     * 同一个标识，其他 OpenAI Compatible 网关不会收到该专用请求头。</p>
     *
     * @param config 当前租户已校验的模型配置
     * @param agent 本次运行的 Agent 定义
     * @param credential 与当前租户及模型配置匹配的受控凭据
     * @param runId 当前运行标识；为 {@code null} 时保持通用工厂兼容行为
     * @return 新创建的 AgentScope 模型实例
     */
    Model create(ModelConfig config, AgentDefinition agent, ModelCredential credential, UUID runId) {
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
            case OPENAI_COMPATIBLE -> createOpenAiCompatibleModel(
                    config, credential, modelName, options, runId);
            case DASHSCOPE_NATIVE -> DashScopeChatModel.builder()
                    .apiKey(credential.apiKey())
                    .baseUrl(config.baseUrl())
                    .modelName(modelName)
                    .stream(true)
                    .defaultOptions(options)
                    .build();
        };
    }

    /**
     * 创建 OpenAI Compatible 模型，并仅为 OpenCode Go 注入其协议要求的会话头。
     *
     * @param config 当前租户已校验的模型配置
     * @param credential 与当前租户及模型配置匹配的受控凭据
     * @param modelName 本次实际调用的模型名称
     * @param options 本次生成选项
     * @param runId 当前运行标识；可为 {@code null}
     * @return 新创建的 OpenAI Compatible 模型
     */
    private static Model createOpenAiCompatibleModel(
            ModelConfig config,
            ModelCredential credential,
            String modelName,
            GenerateOptions options,
            UUID runId
    ) {
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(credential.apiKey())
                .baseUrl(config.baseUrl())
                .modelName(modelName)
                .stream(true)
                .generateOptions(options);
        if (runId != null && ProviderSessionHeaderHttpTransport.isOpenCodeBaseUrl(config.baseUrl())) {
            HttpTransport transport = new ProviderSessionHeaderHttpTransport(
                    HttpTransportFactory.getDefault(), runId.toString());
            builder.httpTransport(transport);
        }
        return builder.build();
    }
}
