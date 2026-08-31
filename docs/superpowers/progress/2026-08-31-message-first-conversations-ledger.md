# 消息一等公民会话模型进度账本

## 关联文档

- [设计说明](../specs/2026-08-31-message-first-conversations-design.md)
- [实施计划](../plans/2026-08-31-message-first-conversations.md)
- [实现说明](../implementation/2026-08-31-message-first-conversations-implementation-design.md)

## 任务状态

| 任务 | 状态 | 结果 |
| --- | --- | --- |
| 文档复核与用户确认 | 已完成 | 用户确认历史窗口、工具摘要、兼容 API 与范围排除项 |
| Core 领域合同 | 已完成 | Conversation/Message/内容块/Runtime 兼容合同已实现 |
| JDBC、memory 与 V10 | 已完成 | PostgreSQL/MySQL 方言迁移及两类 Repository 已实现 |
| AgentScope 映射 | 已完成 | 最终 Msg、文本事件、工具安全摘要和 conversation sessionId 已接通 |
| 会话编排与历史窗口 | 已完成 | 分段事务、40 条/60,000 字符窗口和失败 Run 标记已实现 |
| REST、SSE 与控制台 | 已完成 | 会话 API、消息级事件和运行页会话交互已实现 |
| 测试与生产文档 | 已完成 | 生产文档、本地非容器回归、打包及远程 PostgreSQL/MySQL 容器验证均已完成 |

## 已执行验证

- JDK 21 下 `mvn -pl cm-agent-server -am -DskipTests compile`：通过。
- JDK 21 下 Core、AgentScope Adapter、Prompt Composer、memory store 选择测试：通过。
- JDK 21 下会话同步消息、SSE 生命周期、跨租户 404、旧 Runtime 空增量兼容选择测试：通过。
- JDK 21 下 AgentScope 最终消息文本/工具块顺序与原始工具内容隔离测试：通过。
- JDK 21 下 `mvn -pl cm-agent-persistence -am -DskipTests test-compile`：通过。
- `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`：通过。
- JDK 21 下 `mvn -pl cm-agent-console test`：通过。
- JDK 21 下服务端及依赖模块的完整非容器测试（排除 JDBC/Flyway/Testcontainers、浏览器冒烟和受用户既有配置影响的 profile 配置测试）：通过。
- JDK 21 下 `mvn -DskipTests package`：通过。
- `git diff --check`：通过，仅输出仓库既有的行尾转换提醒。

## 远程容器验证

- 已将当前 `HEAD` 与未提交改动组合为临时副本并同步到 `ssh rocky`，归档 SHA-256 已在本机与服务器核对一致；未提交、未推送 Git，也未同步用户本地的三份个性化配置文件。
- 在远程 Docker 和 `maven:3.9.9-eclipse-temurin-21` 中执行 `mvn -pl cm-agent-persistence -am test`。PostgreSQL 16.14 与 MySQL 8.4 均成功执行 V1--V10 迁移；除 `MigrationTest` 的迁移总数旧断言外，其余 49 项持久化测试通过。
- 将该断言从 9 修正为 10 后，重新同步并执行 `mvn -pl cm-agent-persistence -am -Dtest=MigrationTest -Dsurefire.failIfNoSpecifiedTests=false test`：2 项通过、0 失败、0 错误；两种数据库的 V10 迁移、表/字段注释、索引和外键断言均通过。

## 受限验证

- 未隔离 `ApplicationProfileConfigurationTest` 的本地非容器回归共出现 27 个失败，均来自任务开始前已存在的本地配置修改改变了 profile 安全校验的失败优先级；本次未覆盖这些用户修改。排除该类后其余非容器测试通过。

## 遗留问题

- 当前不提供消息编辑/删除、会话归档、附件、多模态、HITL、自动摘要、手动取消或写请求幂等重放。
- memory 模式重启后会话和消息丢失，只用于本地和测试。
- 无文本增量的 AgentScope 运行不会发送 `message-started`，以避免伪造未观察到的消息事件；最终消息仍以 completed 为准。

## 提交信息

已提交：`feat: 实现消息一等公民会话模型`（本提交）。
