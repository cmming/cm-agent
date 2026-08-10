# 全局错误诊断实施计划

> **给代理执行者：** 必须按任务顺序执行；每个任务先写失败测试、确认失败原因，再写最小实现并重新运行测试。

**目标：** 建立从前端错误编号到 REST、Agent、Tool、MCP 后台日志的安全诊断闭环。

**架构：** P0 由 HTTP 过滤器提供请求关联标识、全局异常处理器提供 API 失败日志、控制台展示结构化错误；P1 用一个无状态诊断组件统一执行边界日志，避免在底层执行器重复记录；P2 用单元、Web 和脚本测试固定契约。

**技术栈：** Java 21、Spring Boot 3.5、SLF4J、MockMvc、JUnit Jupiter、AssertJ、Mockito、原生浏览器脚本测试。

## 全局约束

- 新增代码、注释、测试和文档使用中文。
- 不修改现有 Flyway、数据库结构或用户的 YAML 改动。
- 不记录 Secret、JWT、Cookie、请求原文、模型输入输出、完整内部 URL、SQL 或未脱敏异常消息。
- 每个系统失败响应使用同一个 `errorId` 关联日志；受控校验和权限失败不打印重复堆栈。
- 构建使用 JDK 21；JDBC/Testcontainers 验证不在本任务范围。

---

### 任务 1：P0 REST 错误关联基础设施

**文件：**

- 新建：`cm-agent-server/src/main/java/com/cmagent/server/web/RequestCorrelationFilter.java`
- 新建：`cm-agent-server/src/main/java/com/cmagent/server/diagnostic/ErrorDiagnosticLogger.java`
- 修改：`cm-agent-api/src/main/java/com/cmagent/api/ApiErrorResponse.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/web/ApiExceptionHandler.java`
- 测试：`cm-agent-server/src/test/java/com/cmagent/server/web/ApiExceptionHandlerTest.java`
- 测试：`cm-agent-server/src/test/java/com/cmagent/server/web/RequestCorrelationFilterTest.java`

**接口：**

- `RequestCorrelationFilter` 将安全 `X-Request-Id` 写入请求属性、MDC 和响应头。
- `ErrorDiagnosticLogger#error(String errorId, String operation, ApiErrorCode errorCode, Throwable failure, DiagnosticContext context)` 记录脱敏堆栈。
- `ApiErrorResponse` 新增 `String errorId`，响应构造器拒绝空编号。

- [ ] 编写过滤器和异常处理器的失败测试：验证生成/继承请求编号、503 响应编号、异常日志脱敏和校验异常不含堆栈。
- [ ] 运行单测，确认因为新类型、字段或日志尚不存在而失败。
- [ ] 实现 `RequestCorrelationFilter`、`DiagnosticContext`、`ErrorDiagnosticLogger` 和 `ApiExceptionHandler` 改造。
- [ ] 运行单测，确认请求编号、响应和日志一致。

### 任务 2：P0 控制台结构化失败展示

**文件：**

- 修改：`cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js`
- 测试：`cm-agent-console/src/test/js/console-core.test.cjs`

**接口：**

- `formatError(status, body, fallbackText)` 对非认证类错误返回后端脱敏 `message`，并追加 `code`、`errorId`。

- [ ] 编写 503 结构化错误的失败脚本测试，期望包含原因、错误码和错误编号。
- [ ] 运行 Node 测试，确认当前 5xx 统一文案导致失败。
- [ ] 最小修改 `formatError`，保持 401、403、404 既有安全提示。
- [ ] 重新运行脚本测试。

### 任务 3：P1 Agent 与工具执行诊断日志

**文件：**

- 修改：`cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/runtime/GovernedToolInvocationService.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/mcp/McpPublishedToolCatalog.java`
- 修改：`cm-agent-server/src/main/java/com/cmagent/server/service/ToolDebugService.java`
- 测试：`cm-agent-server/src/test/java/com/cmagent/server/runtime/RunExecutionServiceTest.java`
- 测试：`cm-agent-server/src/test/java/com/cmagent/server/runtime/GovernedToolInvocationServiceTest.java`
- 测试：`cm-agent-server/src/test/java/com/cmagent/server/mcp/McpPublishedToolCatalogTest.java`
- 测试：`cm-agent-server/src/test/java/com/cmagent/server/service/ToolDebugServiceTest.java`

**接口：**

- 上述执行边界统一调用 `ErrorDiagnosticLogger`。
- Agent 运行优先传入 `runId`；工具调用优先传入 `toolCallId`；MCP 传入调用来源 `MCP`。

- [ ] 分别为 Agent 根异常、Tool 返回失败、MCP 返回失败写失败测试，验证日志含关联 ID、错误码、资源和脱敏原因。
- [ ] 运行目标测试，确认日志契约尚未满足。
- [ ] 在编排边界接入诊断组件，不在 `DynamicHttpToolExecutor` 底层 catch 增加重复堆栈。
- [ ] 运行目标测试，确认通过并检查不泄露敏感内容。

### 任务 4：P2 集成验证与文档

**文件：**

- 修改：`docs/tool-development-guide.md`
- 修改：`docs/release-notes.md`
- 新建：`docs/superpowers/implementation/2026-08-10-global-error-diagnostics-implementation-design.md`
- 新建：`docs/superpowers/progress/2026-08-10-global-error-diagnostics-ledger.md`

- [ ] 运行 P0/P1 目标 Java、Web 和 Node 测试。
- [ ] 使用临时测试参数运行控制器测试，避免触碰用户 YAML 配置。
- [ ] 更新开发指南和发布说明，说明 `errorId`、前端展示和日志检索方式。
- [ ] 完成实现说明和进度账本，标明未提交状态。
- [ ] 运行 `git diff --check` 并检查四份同主题文档互相引用。
