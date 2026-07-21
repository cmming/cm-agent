# Task 7：发布管理与工具调试 API 报告

## 变更摘要

- 新增 `ToolDebugService` 与 `ToolDebugResponse`，提供独立 HTTP/已注册 LOCAL 工具调试。调用使用 `DEBUG` 来源和随机 `toolCallId`，不创建或伪造 Agent、Run。
- 新增 `McpPublicationService`，支持 MCP 发布和取消发布；校验 MCP 工具名称、HTTP endpoint/config 一致性、LOCAL 注册定义一致性与已发布名称冲突。
- `ToolController` 新增 `POST /api/tools/{id}/debug`、`PUT/DELETE /api/tools/{id}/mcp-publication`；分别使用 `tool:debug` 与既有 `tool:grant` 权限入口。
- bootstrap admin 增加 `tool:debug`、`tool:mcp:invoke` 权限种子；调试输出中的 HTTP URL 与失败细节不会返回给调用者。
- 发布在 JDBC 事务中执行；memory 模式在审计或写入失败时恢复原发布状态。

## RED/GREEN 证据

- RED：服务测试在实现前因 `ToolDebugService`、`McpPublicationService` 和 `ToolDebugResponse` 不存在而按预期 testCompile 失败。
- RED：新增的失败调试脱敏测试先观察到原始异常/URL 可进入响应；随后实现受控错误及 URL 脱敏。
- GREEN：以下指定测试通过：`ToolDebugServiceTest` 7 项、`McpPublicationServiceTest` 5 项、`ToolControllerTest` 9 项、`AuthControllerTest` 8 项。

## 验证命令

```powershell
$env:JAVA_HOME='F:\java\temurin21\jdk-21.0.11+10'
$env:Path="$env:JAVA_HOME\bin;F:\java\apache-maven-3.9.4\bin;$env:Path"
mvn -q -pl cm-agent-server -am "-Dtest=ToolDebugServiceTest,McpPublicationServiceTest,ToolControllerTest,AuthControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -q -pl cm-agent-server -am "-DskipTests" package
```

两条命令均通过。未运行 Testcontainers/JDBC 集成测试：本任务不涉及迁移，且任务要求无需本地 Docker/Testcontainers。

## 风险与注意事项

- MCP 实际调用端点留给 Task 8；本任务只管理发布状态，不把管理权限误作 Agent 工具授权。
- MCP 发布名校验采用 MCP 2.0 的 ASCII 字母、数字、下划线和连字符规则，长度为 1 到 128。
- Maven 测试会输出 Mockito 动态加载 agent 的 JDK 警告；不影响本次测试结果。
