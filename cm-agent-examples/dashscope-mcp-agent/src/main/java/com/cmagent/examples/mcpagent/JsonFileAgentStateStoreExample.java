package com.cmagent.examples.mcpagent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 演示使用 {@link JsonFileAgentStateStore} 将同一会话的 {@link AgentState} 持久化到本机 JSON 文件。
 *
 * <p>示例先使用第一个 {@link ReActAgent} 写入一条对话并关闭它，再创建全新的 Agent 实例读取同一个
 * 状态目录并继续提问，以验证状态恢复不依赖原 Agent 对象。每次运行都会生成新的会话标识，避免历史
 * 演示文件干扰本次观察；状态文件保存在项目 {@code target/agentscope-state/json-file-demo} 下，
 * 可在运行后自行检查或删除。</p>
 *
 * <p>JSON 文件存储只适用于单机开发或演示，多个应用副本不能共享该目录。生产集群应使用
 * {@code RedisAgentStateStore} 等分布式实现。</p>
 *
 * <p><b>运行前准备：</b>设置环境变量 {@code DASHSCOPE_API_KEY}。</p>
 *
 * <p><b>运行方式：</b></p>
 * <pre>
 *   mvn exec:java -pl cm-agent-examples/dashscope-mcp-agent ^
 *       '-Dexec.mainClass=com.cmagent.examples.mcpagent.JsonFileAgentStateStoreExample'
 * </pre>
 */
public final class JsonFileAgentStateStoreExample {

    private static final String DEFAULT_MODEL_NAME = "qwen3.7-plus";
    private static final String USER_ID = "json-file-demo-user";

    private JsonFileAgentStateStoreExample() {
    }

    /**
     * 写入一轮对话后重建 Agent，并在相同状态槽位继续调用以展示本地文件恢复。
     *
     * @param args 未使用的命令行参数
     */
    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("未设置环境变量 DASHSCOPE_API_KEY，无法调用 DashScope 模型。");
            return;
        }

        Path stateDirectory = Path.of(System.getProperty("user.dir"), "target", "agentscope-state", "json-file-demo");
        String sessionId = "json-file-session-" + UUID.randomUUID();
        RuntimeContext context = RuntimeContext.builder()
                .userId(USER_ID)
                .sessionId(sessionId)
                .build();

        System.out.println("=== JsonFileAgentStateStore 示例 ===");
        System.out.println("状态目录：" + stateDirectory.toAbsolutePath());
        System.out.println("会话标识：" + sessionId);

        try (ReActAgent firstAgent = buildAgent(apiKey, new JsonFileAgentStateStore(stateDirectory))) {
            callAndPrint(firstAgent, "请记住：我的项目代号是北极星。", context, "首次 Agent");
            printState(firstAgent, context, "首次调用后");
        }

        // 新 Agent 使用相同目录与 (userId, sessionId)，框架会在 call 入口自动加载上一轮保存的 AgentState。
        try (ReActAgent restartedAgent = buildAgent(apiKey, new JsonFileAgentStateStore(stateDirectory))) {
            callAndPrint(restartedAgent, "我的项目代号是什么？只根据当前会话历史回答。", context, "重启后的 Agent");
            printState(restartedAgent, context, "恢复并继续调用后");
        }
    }

    /**
     * 使用与 {@link McpStreamableHttpExample} 相同的 DashScope 模型配置创建 Agent。
     *
     * @param apiKey DashScope API Key，仅用于认证，不记录或输出
     * @param stateStore 本机 JSON 文件状态存储
     * @return 已配置状态存储的 Agent
     */
    private static ReActAgent buildAgent(String apiKey, JsonFileAgentStateStore stateStore) {
        return ReActAgent.builder()
                .name("JsonFileStateDemoAgent")
                .sysPrompt("你是状态存储演示助手。只根据当前会话的对话历史回答问题。")
                .model(DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(DEFAULT_MODEL_NAME)
                        .stream(true)
                        .formatter(new DashScopeChatFormatter())
                        .build())
                .stateStore(stateStore)
                .build();
    }

    private static void callAndPrint(ReActAgent agent, String question, RuntimeContext context, String stage) {
        Msg reply = Objects.requireNonNull(
                agent.call(List.of(new UserMessage(question)), context).block(),
                "模型未返回回复"
        );
        System.out.println("\n[" + stage + "] 用户：" + question);
        System.out.println("[" + stage + "] 智能体：" + reply.getTextContent());
    }

    private static void printState(ReActAgent agent, RuntimeContext context, String stage) {
        AgentState state = agent.getAgentState(context);
        System.out.printf("[%s] userId=%s，sessionId=%s，上下文消息数=%d%n",
                stage, state.getUserId(), state.getSessionId(), state.getContext().size());
    }
}
