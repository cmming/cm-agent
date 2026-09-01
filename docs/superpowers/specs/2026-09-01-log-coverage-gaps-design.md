# 关键位置日志输出补齐设计（2026-09-01-log-coverage-gaps）

## 背景

AGENTS.md 的"错误诊断、日志与前端提示规范"要求失败边界必须留下含关联标识的键值日志，生命周期状态变化可留 INFO。前期任务（2026-08-10 全局错误诊断、工具调试可观测性）已在 Web 异常边界、SSE 错误流、MCP Servlet、AgentScope 执行器与工具桥接器建立了完整日志，但系统排查发现仍有一批关键位置完全没有日志输出：

- JWT 解析失败的 catch 完全静默，过期/伪造令牌在应用日志不可见。
- MCP 目录中"权限拒绝但审计写入失败"、审计写入失败（helper 内部）、工具输入 JSON 规范化失败三条路径直接转换为协议错误或受控结果，无任何日志。
- 无事务模式下工具状态补偿失败只挂 suppressed 异常，可能残留部分写入却不可见。
- Agent 凭据解析失败被映射为受控失败终态，catch 内无日志。
- bootstrap admin 登录失败无 WARN，登录成功无 INFO。
- Run 启动/完成只写审计表，无应用日志。
- memory 持久化模式、FakeAgentRuntime 默认装配、Flyway 方言选择与迁移完成、JDBC 默认数据初始化等启动期关键状态完全静默。

## 目标

1. 所有"捕获后转换为受控结果/协议错误"的失败边界都有可检索的应用日志。
2. 启动期关键状态（持久化模式、运行时类型、Flyway 迁移结果）有一次性 INFO。
3. Run 生命周期与登录状态变化留 INFO/WARN。
4. 日志不泄露 token、密码、SQL、模型输入输出、JDBC URL 与密钥。

## 非目标

- 不修改统一异常边界（`ApiExceptionHandler`）——它是全仓库唯一 REST ERROR 出口，已合规。
- 不给 JDBC Repository 层加日志——其异常统一上抛由全局边界记录，重复打印违反规范。
- 不调整错误码体系、审计结构或前端错误响应协议。
- 不引入新日志库或新配置项，全部使用既有 SLF4J API。
- 不给 `ResponseStatusException` 类 4xx 业务失败（404/409 等普通拒绝）补日志，避免参数校验噪音。

## 方案

| 位置 | 级别 | 说明 |
|---|---|---|
| `JwtAuthenticationFilter` JWT 解析 catch | WARN | 只记异常类型，不记 token/Authorization 头/异常消息 |
| `McpPublishedToolCatalog` 三处失败路径 | ERROR | 审计写入失败、输入 JSON 失败；生成随机 errorId 作本地关联编号 |
| `ManagementCommandService#compensate` | WARN | 新增 step 参数标识补偿步骤；不重复打印主异常堆栈 |
| `AgentScopeRuntimeAdapter` 两处凭据 catch | WARN | 只记 runId/tenantId/modelConfigId/exceptionType |
| `RunPersistenceService.start/complete` | INFO | runId/tenantId/agentId/status/toolCallCount |
| `AuthController.login` 成功/失败 | INFO/WARN | 只记用户名，绝不记密码与 token |
| `ServerRepositoryConfiguration` memory 装配 | INFO | 提示数据不落盘 |
| `CmAgentAutoConfiguration` FakeAgentRuntime 装配 | INFO | 提示未接真实模型 |
| `CmAgentFlyway` 方言选择 | INFO | database + dialect |
| `JdbcPersistenceConfiguration` 迁移与默认数据 | INFO | migrationsExecuted / tenantId + modelProviderId |

## 约束

- starter 模块因此需要显式声明 `slf4j-api` 依赖（`spring-boot-autoconfigure` 不传递 slf4j）。
- persistence 模块经 `spring-boot-starter-jdbc` 传递 slf4j-api，无需改 POM。
- 所有新增日志遵守：只记标识符（UUID、枚举名、用户名），不记原文内容；异常只记类型或经既有脱敏器处理后的消息。

## 验收标准

1. `mvn -pl cm-agent-server -am test` 全部通过。
2. Maven 编译、打包无错误。
3. 新增日志行均含结构化键值（`runId=`、`exceptionType=` 等）且无敏感原文。
4. `docs/superpowers` 四份文档同步生成且主题一致。
