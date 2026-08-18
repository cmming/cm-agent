package com.cmagent.examples.mcpagent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;

import java.util.List;
import java.util.Objects;

/**
 * 演示 AgentScope {@link RuntimeContext} 与 {@link AgentState} 的会话隔离、自动恢复和清空行为。
 *
 * <p>本示例复用 {@link McpStreamableHttpExample} 的 DashScope 模型配置方式，但不依赖 MCP 服务：
 * 同一个 {@link ReActAgent} 实例通过每次调用传入的 {@code userId}/{@code sessionId} 定位状态。
 * 第一段会话连续发起两次提问以验证上下文延续；第二段会话使用相同用户但不同会话标识，
 * 用于展示其状态不会与第一段会话混用。</p>
 *
 * <p>示例显式使用 {@link InMemoryAgentStateStore}，以便在单次进程内直观观察状态；
 * 进程退出后状态会丢失。生产多副本部署应改用 Redis 等分布式状态存储，不能照搬此配置。</p>
 *
 * <p><b>运行前准备：</b>设置环境变量 {@code DASHSCOPE_API_KEY} 为阿里云百炼 API Key。</p>
 *
 * <p><b>运行方式：</b></p>
 * <pre>
 *   mvn exec:java -pl cm-agent-examples/dashscope-mcp-agent ^
 *       '-Dexec.mainClass=com.cmagent.examples.mcpagent.ContextAndAgentStateExample'
 * </pre>
 */
public final class ContextAndAgentStateExample {

        /** 通义千问模型名称，用于构建 DashScope 模型客户端。 */
    private static final String DEFAULT_MODEL_NAME = "qwen3.7-plus";

    /**
     * 演示用户标识；两个会话共享同一 userId，但通过不同的 sessionId 实现状态隔离。
     * 与 {@link RuntimeContext#userId} 配合使用，决定 {@link AgentState} 的存取位置。
     */
    private static final String DEMO_USER_ID = "demo-user";

    /**
     * 第一段会话的会话标识。该会话连续发起两次提问，用于验证上下文延续：
     * 第二次提问能回忆起第一次中告知的偏好信息。
     */
    private static final String PREFERENCE_SESSION_ID = "preference-session";

    /**
     * 第二段会话的会话标识。与 {@link #PREFERENCE_SESSION_ID} 使用相同用户但不同 sessionId，
     * 用于展示其状态完全独立，不会携带第一段会话的对话历史。
     */
    private static final String ISOLATED_SESSION_ID = "isolated-session";



    private ContextAndAgentStateExample() {
    }

    /**
     * 启动两个会话并输出每次调用后由框架自动维护的 {@link AgentState} 摘要。
     *
     * @param args 未使用的命令行参数
     */
    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("未设置环境变量 DASHSCOPE_API_KEY，无法调用 DashScope 模型。");
            return;
        }

        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        try (ReActAgent agent = buildAgent(apiKey, stateStore)) {
            System.out.println("=== 上下文与 AgentState 示例 ===\n");

            callAndPrint(agent, "请记住：我喜欢蓝色。", PREFERENCE_SESSION_ID, "context-demo-001");
            callAndPrint(agent, "我刚才喜欢什么颜色？只根据当前会话的对话历史回答。",
                    PREFERENCE_SESSION_ID, "context-demo-002");
            printState(agent, PREFERENCE_SESSION_ID);

            System.out.println("\n--- 使用不同 sessionId 的隔离会话 ---");
            callAndPrint(agent, "这是一个全新会话。请问我喜欢什么颜色？如果不知道请明确说明。",
                    ISOLATED_SESSION_ID, "context-demo-003");
            printState(agent, ISOLATED_SESSION_ID);

            System.out.println("\n--- 清空 preference-session 的对话上下文 ---");
            // clearContext 只清空模型可见的对话与摘要，仍保留同一 userId/sessionId 的其他状态槽位。
            agent.clearContext(DEMO_USER_ID, PREFERENCE_SESSION_ID);
            printState(agent, PREFERENCE_SESSION_ID);
        }
    }

    /**
     * 构建共享的无状态 Agent 引擎；每次调用的可变会话数据由 {@code RuntimeContext} 选择的状态槽位承载。
     *
     * @param apiKey DashScope API Key，仅用于模型客户端认证，禁止记录或输出
     * @param stateStore 单进程演示用状态存储
     * @return 已绑定 DashScope 模型和状态存储的 Agent
     */
    private static ReActAgent buildAgent(String apiKey, InMemoryAgentStateStore stateStore) {
        return ReActAgent.builder()
                .name("ContextStateDemoAgent")
                .sysPrompt("你是上下文演示助手。回答时只依据当前会话的对话历史，"
                        + "不要把其他会话中的信息带入当前回复。")
                .model(DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(DEFAULT_MODEL_NAME)
                        .stream(true)
                        .formatter(new DashScopeChatFormatter())
                        .build())
                .stateStore(stateStore)
                .build();
    }

    /**
     * 在指定状态槽位执行一次调用，并输出模型的最终文本回复。
     *
     * @param agent 共享的 Agent 实例
     * @param question 用户问题
     * @param sessionId 会话标识；与用户标识共同决定 AgentState 的存取位置
     * @param requestId 仅属于本次调用的关联元数据，不会持久化进 AgentState
     */
    private static void callAndPrint(ReActAgent agent, String question, String sessionId, String requestId) {
        RuntimeContext context = RuntimeContext.builder()
                .userId(DEMO_USER_ID)
                .sessionId(sessionId)
                .put("requestId", requestId)
                .build();
        Msg reply = Objects.requireNonNull(
                agent.call(List.of(new UserMessage(question)), context).block(),
                "模型未返回回复"
        );

        System.out.println("\n[" + sessionId + "] 用户：" + question);
        System.out.println("[" + sessionId + "] 智能体：" + reply.getTextContent());
    }

    /**
     * 读取已自动保存的状态，并验证 JSON 序列化后的快照可恢复为相同的会话归属和上下文数量。
     *
     * @param agent 共享的 Agent 实例
     * @param sessionId 待检查的会话标识
     */
    private static void printState(ReActAgent agent, String sessionId) {
        AgentState state = agent.getAgentState(DEMO_USER_ID, sessionId);
        AgentState restored = AgentState.fromJsonString(state.toJson());
        System.out.printf("会话状态：userId=%s，sessionId=%s，上下文消息数=%d，恢复后消息数=%d%n",
                state.getUserId(),
                state.getSessionId(),
                state.getContext().size(),
                restored.getContext().size());
    }
}
