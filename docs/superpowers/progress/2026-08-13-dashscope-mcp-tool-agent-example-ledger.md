# AgentScope + DashScope + MCP 时间工具示例进度账本

对应设计：[设计文档](../specs/2026-08-13-dashscope-mcp-tool-agent-example-design.md)；对应计划：[实施计划](../plans/2026-08-13-dashscope-mcp-tool-agent-example.md)；对应实现说明：[实现说明](../implementation/2026-08-13-dashscope-mcp-tool-agent-example-implementation-design.md)。

| 任务 | 状态 | 验证结果 | 备注 |
| --- | --- | --- | --- |
| Task 1：新增示例模块骨架 | 已完成 | 在切换到本机 `F:\java\temurin21\jdk-21.0.11+10`（JDK 21）后，`mvn -q -pl cm-agent-examples/dashscope-mcp-agent -am dependency:resolve` 与 `mvn -q -pl cm-agent-examples/dashscope-mcp-agent -am "-DskipTests" package` 均成功；`io.agentscope:agentscope-extensions-model-dashscope:2.0.0` 依赖可正常解析（本机 Maven 本地仓库位于 `F:\mnt\f\java\maven-repository`，而非默认 `~/.m2`）。 | 首次未切换 JDK 时使用系统默认 JDK 17 编译，报 `不支持发行版本 21`；切换 `JAVA_HOME` 后复测通过。 |
| Task 2：实现示例主类 | 已完成 | 同上 `package` 命令验证编译通过；`get_errors` 工具确认 `DashScopeMcpToolExample.java` 与模块 `pom.xml` 均无编译错误。关键 API（`McpClientBuilder`、`Toolkit#registerMcpClient`、`ReActAgent#call(String, RuntimeContext)`、`DashScopeChatModel.builder()` 默认 `baseUrl`）均通过反编译 `agentscope-core-2.0.0.jar`、`agentscope-extensions-model-dashscope-2.0.0.jar` 字节码逐一核实签名与行为后再落地代码。 | 端到端人工运行（`mvn -pl cm-agent-examples/dashscope-mcp-agent exec:java`）在本沙箱环境中长时间挂起未返回：本机 `localhost:8088` 未运行任何 MCP 服务，MCP Streamable HTTP 传输的连接/握手在此条件下未在预期时间内失败退出。**未能在本沙箱完成真正的端到端联调**，原因是沙箱环境不具备用户描述的本地 MCP 时间查询服务，也无法确认沙箱对 DashScope 公网地址的可达性；该验证需要使用者在已启动对应 MCP 服务、且网络可访问 `https://dashscope.aliyuncs.com` 的机器上自行执行 `mvn -pl cm-agent-examples/dashscope-mcp-agent exec:java` 完成。 |
| Task 3：文档同步 | 已完成 | 已在 `docs/release-notes.md` 补充本次变更说明。 | — |
| Task 4：全量回归确认 | 部分完成 | 仅验证新增模块自身编译打包成功，未运行仓库全量 `mvn -q test`（本次改动不涉及其他模块代码，且新增模块无自动化测试）。 | 未在本次任务中执行 `mvn -q test` 全量回归；根据设计范围，本次改动不修改任何生产模块，风险可控。 |
| Task 5：修复 MCP 依赖版本冲突导致的 `NoSuchMethodError`（用户实测反馈） | 已完成 | 使用者在本机真实 MCP 服务上运行后反馈：MCP `initialize` 成功，但注册工具阶段抛出 `NoSuchMethodError: McpSchema$Tool.inputSchema()`。排查确认 `agentscope-core:2.0.0` 实际编译依赖 `mcp-core:0.17.0`（`Tool.inputSchema()` 返回 `JsonSchema`），而父 POM `dependencyManagement` 把该构件统一管理为 `2.0.0`（`Tool.inputSchema()` 返回 `Map<String,Object>`），二者二进制不兼容。已在 `dashscope-mcp-agent/pom.xml` 内显式锁定 `mcp-core`/`mcp-json-jackson2` 为 `0.17.0`，`mvn -pl cm-agent-examples/dashscope-mcp-agent dependency:tree` 与 `dependency:build-classpath` 确认最终 classpath 只含一致的 `0.17.0` 系列构件；`mvn -q -pl cm-agent-examples/dashscope-mcp-agent -am "-DskipTests" package`（JDK 21）重新编译打包成功。 | 修复只影响本示例模块，未修改父 POM 的 `mcp.version`（仍为 `2.0.0`），`cm-agent-server` 自身 MCP Streamable HTTP Server 不受影响。受限于沙箱无法访问用户本机真实 MCP 服务，本次未能在本沙箱重新验证"修复后完整跑通并得到模型回复"，需使用者在其本机重新执行 `mvn -pl cm-agent-examples/dashscope-mcp-agent exec:java` 确认不再出现 `NoSuchMethodError` 且能得到模型最终回复。 |

## 遗留问题

- 示例的端到端真实调用（连接本地 MCP 服务 + 调用 DashScope 真实模型）未在当前沙箱环境验证，需要使用者在具备对应 MCP 服务和公网访问的环境中自行运行确认。
- `NoSuchMethodError` 的依赖版本冲突已在 `dashscope-mcp-agent` 模块内修复（锁定 `mcp-core`/`mcp-json-jackson2` 为 `0.17.0`），但**尚未在使用者本机重新验证**该修复确实消除了报错并能完整得到模型回复；需使用者本机复测并反馈。
- 若后续升级 `agentscope-core` 版本，需要重新核对其实际依赖的 MCP SDK 版本，不能默认与父 POM `mcp.version` 一致（详见实现说明第 7 节）。

## 提交信息

未提交。

