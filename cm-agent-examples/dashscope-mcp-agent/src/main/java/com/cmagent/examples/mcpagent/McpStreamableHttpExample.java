package com.cmagent.examples.mcpagent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 通过 MCP Streamable HTTP 传输协议连接外部 MCP 服务的交互式示例。
 *
 * <p>Streamable HTTP 是较新的 MCP 传输方式，使用单一 HTTP 端点配合流式响应，
 * 相比 SSE 不需要保持长连接，且与标准 HTTP 基础设施（代理、负载均衡、API 网关）兼容性更好。</p>
 *
 * <p>整体流程：</p>
 * <ol>
 *   <li>通过环境变量 {@code MCP_HTTP_URL} 指定 MCP 服务端点（默认 {@code http://localhost:8088/api/mcp}）；</li>
 *   <li>使用 {@link McpClientBuilder#streamableHttpTransport(String)} 构建 Streamable HTTP 客户端；</li>
 *   <li>将 MCP 服务暴露的工具注册到 AgentScope 的 {@link Toolkit}；</li>
 *   <li>创建绑定通义千问模型的 {@link ReActAgent}，以流式方式输出回复；</li>
 *   <li>进入交互式对话循环，用户输入 {@code exit} 或 {@code quit} 结束。</li>
 * </ol>
 *
 * <p><b>运行前准备：</b></p>
 * <ul>
 *   <li>设置环境变量 {@code DASHSCOPE_API_KEY} 为阿里云百炼 API Key；</li>
 *   <li>（可选）设置 {@code MCP_HTTP_URL} 指向目标 MCP 服务端点；</li>
 *   <li>（可选）设置 {@code MCP_HTTP_API_KEY} 为 MCP 服务的 API Key（会以 {@code X-API-Key} 请求头发送）。</li>
 * </ul>
 *
 * <p><b>运行方式：</b></p>
 * <pre>
 *   mvn exec:java -pl cm-agent-examples/dashscope-mcp-agent ^
 *       -Dexec.mainClass=com.cmagent.examples.mcpagent.McpStreamableHttpExample
 * </pre>
 *
 * <p>本示例仅用于本地联调演示，生产环境请通过受控配置或密钥管理服务读取凭据，
 * 切勿在生产代码中硬编码真实 API Key。</p>
 */
public final class McpStreamableHttpExample {

    /** 未指定环境变量时使用的默认 MCP 服务地址（指向本地 cm-agent-server 的 MCP 端点）。 */
    private static final String DEFAULT_MCP_URL = "http://localhost:8088/api/mcp";

    /** 未指定环境变量时使用的默认模型名称。 */
    private static final String DEFAULT_MODEL_NAME = "qwen3.7-plus";


    private McpStreamableHttpExample() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("MCP Streamable HTTP 交互示例");
        System.out.println("=".repeat(60));
        System.out.println("通过 Streamable HTTP 传输协议连接 MCP 服务，并以流式方式输出智能体回复。");
        System.out.println("启动前请设置 MCP_HTTP_URL 指向目标 MCP 服务端点。");
        System.out.println("=".repeat(60) + "\n");

        // ── 读取凭据与配置 ───────────────────────────────────────────────
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("未设置环境变量 DASHSCOPE_API_KEY，请先配置后再运行。");
            return;
        }

        String httpUrl = System.getenv("MCP_HTTP_URL");
        if (httpUrl == null || httpUrl.isBlank()) {
            httpUrl = DEFAULT_MCP_URL;
            System.out.println("MCP_HTTP_URL 未设置，使用默认地址：" + httpUrl);
        }

        // ── 构建 MCP Streamable HTTP 客户端 ──────────────────────────────
        //
        // streamableHttpTransport(url) — 连接到 HTTP 流式端点。
        // header(name, value) — 添加 HTTP 请求头（例如 X-API-Key）。
        McpClientBuilder builder = McpClientBuilder.create("http-mcp-server")
                .streamableHttpTransport(httpUrl);

        String httpApiKey = System.getenv("MCP_HTTP_API_KEY");
        if (httpApiKey != null && !httpApiKey.isBlank()) {
            builder.header("X-API-Key", httpApiKey);
            System.out.println("已添加 X-API-Key 请求头。");
        }

        System.out.print("正在连接 Streamable HTTP MCP 服务：" + httpUrl + " ...");
        try (McpClientWrapper mcpClient = builder.buildAsync().block()) {
            System.out.println(" 连接成功！\n");

            // ── 注册 MCP 工具到 Toolkit ──────────────────────────────────
            Toolkit toolkit = new Toolkit();
            System.out.print("正在注册 MCP 工具 ...");
            toolkit.registerMcpClient(mcpClient).block();
            System.out.println(" 完成（已注册工具：" + toolkit.getToolNames() + "）\n");

            // ── 构建 ReAct 智能体 ────────────────────────────────────────
            ReActAgent agent = ReActAgent.builder()
                    .name("HttpMcpAgent")
                    .sysPrompt("你是一个可以通过 MCP 协议调用外部工具的智能助手，"
                            + "请根据用户问题合理选择并调用可用工具。")
                    .model(DashScopeChatModel.builder()
                            .apiKey(apiKey)
                            .modelName(DEFAULT_MODEL_NAME)
                            .stream(true)
                            .formatter(new DashScopeChatFormatter())
                            .build())
                    .toolkit(toolkit)
                    // AgentScope 2.0.0 新增工具调用 human-in-the-loop 权限确认：未配置权限上下文时，
                    // 非只读 MCP 工具（McpTool）默认判定为 ASK，智能体会暂停并抛出
                    // IllegalStateException，等待下一条消息携带 ConfirmResult 确认结果后才能继续。
                    // 本示例为最小可运行演示，不处理确认交互，因此显式使用 BYPASS 模式放行全部工具调用。
                    .permissionContext(PermissionContextState.builder()
                            .mode(PermissionMode.BYPASS)
                            .build())
                    .build();

            // ── 交互式对话循环 ───────────────────────────────────────────
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("对话已开始，输入 'exit' 或 'quit' 结束。\n");

            while (true) {
                System.out.print("用户: ");
                String input = reader.readLine();
                if (input == null || input.trim().equalsIgnoreCase("exit")
                        || input.trim().equalsIgnoreCase("quit")) {
                    System.out.println("\n再见！");
                    break;
                }
                if (input.isBlank()) {
                    continue;
                }

                Msg userMsg = new UserMessage(input.trim());
                System.out.print("\n智能体: ");
                agent.streamEvents(userMsg)
                        .doOnNext(event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            }
                        })
                        .blockLast();
                System.out.println("\n");
            }
        }
    }
}

