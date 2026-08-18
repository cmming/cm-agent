package com.cmagent.examples.mcpagent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 演示使用 {@link RedisAgentStateStore} 在独立 Agent 实例之间共享同一会话的 {@link AgentState}。
 *
 * <p>示例模拟两个应用副本：副本 A 将首轮会话写入 Redis，副本 B 创建独立的 Jedis 和状态存储对象后，
 * 使用相同的 {@code (userId, sessionId)} 继续调用。框架会自动从 Redis 恢复对话上下文。实际部署中，
 * 两个副本可以位于不同 JVM 或不同机器。</p>
 *
 * <p>Redis URL 通过 {@code REDIS_URL} 配置，缺省连接 Rocky 联调服务器的
 * {@code redis://192.168.0.66:6379}。URL 可能包含密码，示例不会将其输出到控制台或日志。仅配置
 * {@code RedisAgentStateStore} 覆盖的是会话状态；若同时
 * 使用 AgentScope 的远程工作区或沙箱，还需要按框架文档配置 {@code RedisDistributedStore}。</p>
 *
 * <p><b>运行前准备：</b>启动可访问的 Redis，并设置环境变量 {@code DASHSCOPE_API_KEY}；可选设置
 * {@code REDIS_URL}。</p>
 *
 * <p><b>运行方式：</b></p>
 * <pre>
 *   mvn exec:java -pl cm-agent-examples/dashscope-mcp-agent ^
 *       '-Dexec.mainClass=com.cmagent.examples.mcpagent.RedisAgentStateStoreExample'
 * </pre>
 */
public final class RedisAgentStateStoreExample {

    private static final String DEFAULT_MODEL_NAME = "qwen3.7-plus";
    private static final String USER_ID = "redis-demo-user";
    private static final String KEY_PREFIX = "cm-agent:example:agent-state:";
    /** 未配置 {@code REDIS_URL} 时连接 Rocky 联调服务器的 Redis。 */
    private static final String DEFAULT_REDIS_URL = "redis://192.168.0.66:6379";

    private RedisAgentStateStoreExample() {
    }

    /**
     * 模拟两个副本通过 Redis 自动保存和恢复同一会话。
     *
     * @param args 未使用的命令行参数
     */
    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("未设置环境变量 DASHSCOPE_API_KEY，无法调用 DashScope 模型。");
            return;
        }

        String redisUrl = System.getenv("REDIS_URL");
        if (redisUrl == null || redisUrl.isBlank()) {
            redisUrl = DEFAULT_REDIS_URL;
        }
        String sessionId = "redis-session-" + UUID.randomUUID();
        RuntimeContext context = RuntimeContext.builder()
                .userId(USER_ID)
                .sessionId(sessionId)
                .build();

        System.out.println("=== RedisAgentStateStore 示例 ===");
        System.out.println("Redis 状态键前缀：" + KEY_PREFIX);
        System.out.println("会话标识：" + sessionId);

        runReplicaA(apiKey, redisUrl, context);
        runReplicaB(apiKey, redisUrl, context);
    }

    /**
     * 模拟第一个应用副本写入会话状态；资源关闭后仍由 Redis 保留状态快照。
     */
    private static void runReplicaA(String apiKey, String redisUrl, RuntimeContext context) {
        RedisAgentStateStore stateStore = createStateStore(new JedisPooled(redisUrl));
        try (ReActAgent agent = buildAgent(apiKey, stateStore, "RedisStateDemoAgentA")) {
            callAndPrint(agent, "请记住：本次发布的代号是天枢。", context, "副本 A");
            printState(agent, context, "副本 A 写入后");
        } finally {
            // RedisAgentStateStore 持有并关闭由 builder 注入的 Jedis 客户端，避免连接池遗留。
            stateStore.close();
        }
    }

    /**
     * 模拟新副本从 Redis 恢复先前的状态；与副本 A 不共享任何 Java 对象。
     */
    private static void runReplicaB(String apiKey, String redisUrl, RuntimeContext context) {
        RedisAgentStateStore stateStore = createStateStore(new JedisPooled(redisUrl));
        try (ReActAgent agent = buildAgent(apiKey, stateStore, "RedisStateDemoAgentB")) {
            callAndPrint(agent, "本次发布的代号是什么？只根据当前会话历史回答。", context, "副本 B");
            printState(agent, context, "副本 B 恢复并继续调用后");
        } finally {
            // RedisAgentStateStore 持有并关闭由 builder 注入的 Jedis 客户端，避免连接池遗留。
            stateStore.close();
        }
    }

    private static RedisAgentStateStore createStateStore(JedisPooled jedis) {
        return RedisAgentStateStore.builder()
                .jedisClient(jedis)
                .keyPrefix(KEY_PREFIX)
                .build();
    }

    /**
     * 使用与 {@link McpStreamableHttpExample} 相同的 DashScope 模型配置创建 Agent。
     *
     * @param apiKey DashScope API Key，仅用于认证，不记录或输出
     * @param stateStore Redis 状态存储
     * @param name 当前模拟副本的 Agent 名称
     * @return 已配置状态存储的 Agent
     */
    private static ReActAgent buildAgent(
            String apiKey,
            RedisAgentStateStore stateStore,
            String name
    ) {
        return ReActAgent.builder()
                .name(name)
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

    private static void callAndPrint(ReActAgent agent, String question, RuntimeContext context, String replica) {
        Msg reply = Objects.requireNonNull(
                agent.call(List.of(new UserMessage(question)), context).block(),
                "模型未返回回复"
        );
        System.out.println("\n[" + replica + "] 用户：" + question);
        System.out.println("[" + replica + "] 智能体：" + reply.getTextContent());
    }

    private static void printState(ReActAgent agent, RuntimeContext context, String stage) {
        AgentState state = agent.getAgentState(context);
        System.out.printf("[%s] userId=%s，sessionId=%s，上下文消息数=%d%n",
                stage, state.getUserId(), state.getSessionId(), state.getContext().size());
    }
}
