package com.cmagent.examples.mcpagent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;

import java.time.Duration;

/**
 * AgentScope + MCP（Streamable HTTP）+ 阿里通义千问模型的最小可运行示例。
 *
 * <p>整体链路：</p>
 * <ol>
 *   <li>通过 MCP Streamable HTTP 协议连接本地 MCP 服务（{@code http://localhost:8088/api/mcp}）；</li>
 *   <li>把该 MCP 服务暴露的工具（其中包含时间查询工具）注册到 AgentScope 的 {@link Toolkit}；</li>
 *   <li>创建绑定通义千问（{@code qwen3.7-plus}）模型的 {@link ReActAgent}，将上述工具集交给它；</li>
 *   <li>发送一条询问当前时间的用户消息，观察模型自动决策调用 MCP 时间查询工具并给出回复。</li>
 * </ol>
 *
 * <p>本示例仅用于本地联调演示，MCP 服务地址、模型名称、API Key 均按需求直接写死；
 * 生产环境请改为从受控配置或密钥管理服务中读取，切勿以此方式硬编码真实凭据。</p>
 */
public final class DashScopeMcpToolExample {

    /** 阿里云百炼（DashScope）API Key，仅用于本地示例演示。 */
    private static final String DASHSCOPE_API_KEY = System.getenv("DASHSCOPE_API_KEY");

    /** 需要调用的通义千问模型名称。 */
    private static final String MODEL_NAME = "qwen3.7-plus";

    /** MCP 服务地址，协议为 Streamable HTTP。 */
    private static final String MCP_SERVER_URL = "http://localhost:8088/api/mcp";

    /** 发送给模型的示例用户问题，用于触发时间查询工具调用。 */
    private static final String USER_QUESTION = "现在几点了？请调用时间查询工具获取准确结果。";

    private DashScopeMcpToolExample() {
    }

    public static void main(String[] args) {
        // 1. 构建 MCP Streamable HTTP 客户端并连接本地 MCP 服务；
        //    2. 构建绑定该 MCP 工具集的 ReAct 智能体。
        //    两个资源都实现了 AutoCloseable，使用 try-with-resources 保证连接/资源正常释放。
        try (McpClientWrapper mcpClient = buildMcpClient();
             ReActAgent agent = buildAgent(mcpClient)) {

            RuntimeContext runtimeContext = RuntimeContext.builder()
                    .userId("demo-user")
                    .sessionId("demo-session")
                    .build();

            System.out.println("已从 MCP 服务注册的工具列表：" + agent.getToolkit().getToolNames());
            System.out.println("用户提问：" + USER_QUESTION);

            // 触发模型执行 ReAct 循环：模型会根据系统提示自动决策是否调用时间查询工具。
            Msg reply = agent.call(USER_QUESTION, runtimeContext).block();
            System.out.println("模型回复：" + (reply == null ? "(无回复)" : reply.getTextContent()));
        } catch (Exception ex) {
            // 示例程序直接打印异常信息，便于本地排查连接、鉴权或模型调用问题。
            System.err.println("调用 MCP 工具或模型失败：" + ex.getMessage());
            ex.printStackTrace();
        }
    }

    /**
     * 构建指向本地 MCP 服务的 Streamable HTTP 客户端。
     */
    private static McpClientWrapper buildMcpClient() {
        return McpClientBuilder.create("time-mcp-server")
                .streamableHttpTransport(MCP_SERVER_URL)
                .timeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30))
                .buildSync();
    }

    /**
     * 创建工具集并注册 MCP 工具，再构建绑定通义千问模型的 ReAct 智能体。
     * {@link Toolkit#registerMcpClient(McpClientWrapper)} 内部会先初始化 MCP 连接，
     * 再拉取工具列表并逐个注册，因此这里无需再手动调用 {@code mcpClient.initialize()}。
     */
    private static ReActAgent buildAgent(McpClientWrapper mcpClient) {

        if (DASHSCOPE_API_KEY == null || DASHSCOPE_API_KEY.isBlank()) {
            System.err.println("未设置环境变量 DASHSCOPE_API_KEY，无法调用 DashScope 模型。");
            return null;
        }

        Toolkit toolkit = new Toolkit();
        toolkit.registerMcpClient(mcpClient).block();

        Model model = DashScopeChatModel.builder()
                .apiKey(DASHSCOPE_API_KEY)
                .modelName(MODEL_NAME)
                .stream(false)
                .defaultOptions(GenerateOptions.builder().temperature(0.3).build())
                .build();

        return ReActAgent.builder()
                .name("time-query-agent")
                .sysPrompt("你是一个可以调用工具的智能助手。当用户询问当前时间等需要实时数据的问题时，"
                        + "必须调用可用的时间查询工具获取结果后再回答，禁止凭空编造时间。")
                .model(model)
                .toolkit(toolkit)
                .maxIters(50)
                // AgentScope 2.0.0 新增工具调用 human-in-the-loop 权限确认：未配置权限上下文时，
                // 非只读 MCP 工具（McpTool）默认判定为 ASK，智能体会暂停并抛出
                // IllegalStateException，等待下一条消息携带 ConfirmResult 确认结果后才能继续。
                // 本示例为最小可运行演示，不处理确认交互，因此显式使用 BYPASS 模式放行全部工具调用。
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS)
                        .build())
                .build();
    }
}

