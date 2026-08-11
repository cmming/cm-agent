# 阶段 2：生产持久化与安全收口进度账本

## 对应文档

- 设计：[阶段 2：生产持久化与安全收口设计](../specs/2026-07-14-phase-2-production-runtime-design.md)
- 计划：[阶段 2：生产持久化与安全收口计划](../plans/2026-07-14-phase-2-production-runtime.md)
- 实现说明：[阶段 2：生产持久化与安全收口实现技术说明](../implementation/2026-07-14-phase-2-production-runtime-implementation-design.md)

## 当前结论

状态：**已完成。**

阶段 2 的范围已包含 Run、ToolCall、AuditEvent 的 JDBC/Flyway 持久化、租户隔离、严格审计、生产 profile/JWT/bootstrap 安全收口、错误脱敏和相关生产文档。本账本用于补齐历史任务缺失的最终交付记录，并跟踪 2026-08-11 发现的 MCP 隔离配置测试回归。

## 任务状态

| 任务 | 状态 | 交付结果 |
| --- | --- | --- |
| 任务 1：领域契约与 Repository | 已完成 | Run、ToolCall、分页契约和 tenant 显式接口已落地。 |
| 任务 2：JDBC/Flyway | 已完成 | V2/V3 索引及 PostgreSQL 16、MySQL 8.4 Repository 测试已落地。 |
| 任务 3：Repository 装配与审计 | 已完成 | JDBC/memory 装配、审计查询和严格审计语义已落地。 |
| 任务 4：运行编排与管理事务 | 已完成 | 两段式运行持久化、ToolCall 写入、查询和管理面事务已落地。 |
| 任务 5：安全与错误边界 | 已完成 | JWT/profile 护栏、统一错误响应和脱敏已落地。 |
| 任务 6：生产文档 | 已完成 | 路线图、配置、部署、运维和发布说明已同步。 |
| MCP 配置测试回归 | 已完成 | 测试夹具已补齐 `ErrorDiagnosticLogger` mock，专项与全量回归均通过。 |

## 2026-08-11 回归记录

### 根因

`McpServerConfigurationTest` 使用 `ApplicationContextRunner` 手工装配 `McpServerConfiguration` 的依赖。后续全局错误诊断改动为 `mcpPublishedToolCatalog` 增加 `ErrorDiagnosticLogger` 参数，但该测试夹具未注册对应 Bean，导致上下文初始化失败，掩盖了原本的 MCP 白名单和端点注册断言。

### 修复范围

仅修改 `cm-agent-server/src/test/java/com/cmagent/server/mcp/McpServerConfigurationTest.java`，为测试上下文增加 `ErrorDiagnosticLogger` mock。未修改生产代码、数据库迁移、API 契约、JWT/profile 配置或用户指定忽略的配置文件。

### 验证记录

| 时间 | 命令/环境 | 结果 |
| --- | --- | --- |
| 2026-08-11 | Rocky Linux VM；Docker 23.0.6；`maven:3.9.9-eclipse-temurin-21`；干净提交 `5e8bae953c2841e16bd0a9c13513871406cca8b6`；执行 `mvn -q -pl cm-agent-server -am -Dtest=McpServerConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 失败：3 个测试中 2 个失败，根因为缺少 `ErrorDiagnosticLogger` Bean。 |
| 2026-08-11 | 相同 Rocky VM 容器；执行修复后的 `mvn -q -pl cm-agent-server -am -Dtest=McpServerConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 通过：3 个测试全部通过；空白名单用例恢复为预期的 `allowedOrigins` 校验失败。 |
| 2026-08-11 | 相同 Rocky VM 容器；执行修复后的完整 `mvn -q test` | 通过：退出码 0；Testcontainers 完成 PostgreSQL 16 和 MySQL 8.4 迁移及相关回归。 |

## 未处理项

按本次任务范围，当前工作区的 `application.yml`、`application-mysql.yml` 和 `application-ok.yml` 配置问题不处理，也不纳入本账本的完成判断。

## 提交信息

未提交。本次仅在工作区修改测试和阶段 2 文档，保留用户已有的无关配置改动。
