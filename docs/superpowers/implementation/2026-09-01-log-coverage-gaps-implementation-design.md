# 关键位置日志输出补齐实现说明（2026-09-01-log-coverage-gaps）

对应设计：`specs/2026-09-01-log-coverage-gaps-design.md`；计划：`plans/2026-09-01-log-coverage-gaps.md`。

## 实际实现与关键代码位置

### cm-agent-server

| 文件 | 变更 |
|---|---|
| `security/JwtAuthenticationFilter.java` | 新增静态 logger；认证 catch 中输出 `log.warn("JWT 认证被拒绝。exceptionType={}", ...)`，注释说明为何只记异常类型（token、Authorization 头、异常消息可能携带 JWT 片段） |
| `mcp/McpPublishedToolCatalog.java` | 1) 权限拒绝时审计写入失败路径：先 `diagnosticLogger.error`（随机 errorId、boundary=MCP_TOOL、errorCode=MCP_AUDIT_UNAVAILABLE、toolId）再抛协议错误；2) 工具输入 JSON 规范化失败：同结构 ERROR（errorCode=MCP_TOOL_INPUT_INVALID）后返回受控失败；3) `failedWithAudit` 内审计失败：同结构 ERROR |
| `service/ManagementCommandService.java` | 新增 logger；`compensate` 增加 `step` 参数并在补偿失败时输出 WARN（step + exceptionType），JavaDoc 说明"主异常由统一边界记录，不在此重复打印"；全部 12 处补偿调用点传入步骤名 |
| `runtime/RunPersistenceService.java` | `start` 两个分支、`complete` 两个分支各输出生命周期 INFO（runId/tenantId/agentId/principalId/status/toolCallCount）；顺带修复 `complete` JavaDoc 过期的 `@param toolCalls` → `authorizedTools` |
| `web/AuthController.java` | 登录失败两分支补 WARN（bootstrap admin 未启用 / 凭据不匹配，均只记用户名），登录成功补 INFO（principalId + tenantId） |
| `config/ServerRepositoryConfiguration.java` | memory 会话 store 装配时输出一次 INFO 提示数据不落盘 |
| `config/JdbcPersistenceConfiguration.java` | `cmAgentFlyway` 输出 `Flyway 迁移完成。migrationsExecuted={}`（读取 `MigrateResult.migrationsExecuted` int 字段）；`defaultTenantDataInitializer` 末尾输出默认数据初始化完成 INFO |

### cm-agent-agentscope-adapter

| 文件 | 变更 |
|---|---|
| `AgentScopeRuntimeAdapter.java` | 新增静态 logger；`run` 与 `runStructured` 两处 `ModelCredentialUnavailableException` catch 输出 WARN（runId/tenantId/modelConfigId/exceptionType），注释说明该 catch 是凭据失败唯一可观察的日志位置 |

### cm-agent-persistence

| 文件 | 变更 |
|---|---|
| `CmAgentFlyway.java` | 新增 logger；方言解析成功后输出 `Flyway 加载数据库方言迁移目录。database={}, dialect={}`；SQLException 分支注释说明为何包装上抛而不重复打印 |

### cm-agent-spring-boot-starter

| 文件 | 变更 |
|---|---|
| `CmAgentAutoConfiguration.java` | `agentRuntime()` 装配 FakeAgentRuntime 前 INFO 提示未接真实模型；JavaDoc 与日志同步 |
| `pom.xml` | 显式添加 `org.slf4j:slf4j-api`（版本由 Spring Boot BOM 管理） |

## 调用链变化

- 无行为变化：所有新增调用都是旁路日志，失败路径的异常类型、传播方向、对外错误响应保持不变。
- `ManagementCommandService#compensate` 签名从 `(Runnable, RuntimeException)` 变为 `(String, Runnable, RuntimeException)`，为 private 方法，无外部调用方。

## 日志内容约束落实

- 所有 WARN/ERROR 日志仅包含：常量、UUID/枚举值、已提交用户名、异常类名；不包含 token、Authorization 头、密码、SQL、模型输入输出、JDBC URL、密钥。
- MCP 目录两条新 ERROR 路径因工具调用请求（含 toolCallId）尚未建立，使用 `UUID.randomUUID()` 生成局部关联编号，并注释说明。

## 与原方案的差异

- Flyway 迁移结果最初误写为 `flyway.migrate()` 返回 int，实际返回 `MigrateResult`，`migrationsExecuted` 字段本身是 int；已修正并加注释说明版本行为。Maven 编译验证为最终依据。
- starter POM 编辑一度引入乱码导致 POM 解析失败，已修复并重新编译验证。
- 收尾核对发现少量受本次编辑影响的既有 JavaDoc 标点和中文字符异常，已恢复为准确中文；不改变任何运行行为。
