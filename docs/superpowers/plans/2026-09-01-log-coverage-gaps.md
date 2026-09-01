# 关键位置日志输出补齐计划（2026-09-01-log-coverage-gaps）

对应设计：`specs/2026-09-01-log-coverage-gaps-design.md`

## 任务拆分与实现顺序

### 任务 1：server 安全与 MCP 失败边界日志
- 文件：
  - `cm-agent-server/src/main/java/com/cmagent/server/security/JwtAuthenticationFilter.java`
  - `cm-agent-server/src/main/java/com/cmagent/server/mcp/McpPublishedToolCatalog.java`
- 内容：JWT 解析 catch 补 WARN；MCP 目录审计写入失败（2 处）、输入 JSON 失败（1 处）补诊断 ERROR。
- 验证：`mvn -pl cm-agent-server -am test`，跑 `McpPublishedToolCatalogTest`、`JwtSecurityConfigurationTest`。

### 任务 2：server 编排与生命周期日志
- 文件：
  - `cm-agent-server/src/main/java/com/cmagent/server/service/ManagementCommandService.java`
  - `cm-agent-server/src/main/java/com/cmagent/server/runtime/RunPersistenceService.java`
  - `cm-agent-server/src/main/java/com/cmagent/server/web/AuthController.java`
- 内容：补偿失败 WARN（带 step）；Run 启动/完成 INFO；登录成功 INFO/失败 WARN。
- 顺带修复：`RunPersistenceService#complete` JavaDoc 的 `@param toolCalls` 过期标签改为 `authorizedTools`。
- 验证：`ManagementCommandServiceTest`、`RunControllerTest` 全部通过。

### 任务 3：启动期状态日志
- 文件：
  - `cm-agent-server/src/main/java/com/cmagent/server/config/ServerRepositoryConfiguration.java`
  - `cm-agent-server/src/main/java/com/cmagent/server/config/JdbcPersistenceConfiguration.java`
  - `cm-agent-persistence/src/main/java/com/cmagent/persistence/CmAgentFlyway.java`
  - `cm-agent-spring-boot-starter/src/main/java/com/cmagent/starter/CmAgentAutoConfiguration.java`
- 内容：memory 模式提示、Flyway 迁移结果与默认数据初始化、方言选择、FakeAgentRuntime 提示。
- 验证：编译通过；有 Docker 环境时跑 persistence 迁移测试。

### 任务 4：adapter 凭据失败日志
- 文件：`cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRuntimeAdapter.java`
- 内容：两处 `ModelCredentialUnavailableException` catch 补 WARN。
- 验证：模块编译 + 既有合同测试。

### 任务 5：starter 依赖修正
- 文件：`cm-agent-spring-boot-starter/pom.xml`
- 内容：显式添加 `org.slf4j:slf4j-api`（autoconfigure 不传递 slf4j，新增日志必须显式依赖）。

### 任务 6：文档
- 生成 `docs/superpowers` 四份同主题文档（specs/plans/implementation/progress）。

## 不改动的位置及原因（评审自查记录)

- `ApiExceptionHandler`：REST 全局 ERROR 出口已有完整诊断日志。
- `Jdbc*Repository` 全部 catch：包装后上抛型，统一边界会记录，重复打印违规。
- `AuditAppender`：WARN + 上抛已合规。
- `McpEndpointServlet`、`AgentScopeReActExecutor`、`AgentScopeToolBridge`：日志已完整。
- `ToolDebugService`、`RunExecutionService`：已有日志覆盖失败收口。

## 整体验证方式

1. `java -version`、`mvn -v` 确认 JDK 21 / Maven 3.9.4。
2. `mvn -pl cm-agent-server -am test`。
3. `mvn -q -DskipTests package` 快速打包。
