# Agent 管理编辑、删除与模型选择进度账本

关联设计：[设计说明](../specs/2026-08-18-agent-management-model-selection-design.md)。

| 任务 | 状态 | 结果 |
| --- | --- | --- |
| 仓储与命令服务 | 已完成 | 支持模型配置校验、更新、历史保护删除和授权清理。 |
| REST 与权限 | 已完成 | 新增 Agent 更新/删除接口和 `agent:delete`。 |
| v2 控制台 | 已完成 | 模型配置下拉选择、编辑、取消编辑和删除确认已实现。 |
| 自动化测试 | 已完成（容器集成测试除外） | 已使用本机 JDK 21 编译，并通过 Agent Web、认证、错误响应、控制台资源和 Node.js 回归测试。 |
| 提交 | 未提交 | 当前工作区尚未创建提交。 |

## 验证记录

- 已使用 `F:\java21` 执行 `mvn -pl cm-agent-server,cm-agent-console,cm-agent-persistence -am -DskipTests compile`，通过。
- 已执行 `mvn -pl cm-agent-server -am -Dtest=AgentControllerTest,AuthControllerTest,ApiExceptionHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test`，通过。
- 已执行 `mvn -pl cm-agent-console -am -Dtest=ConsoleResourceTest -Dsurefire.failIfNoSpecifiedTests=false test`，通过。
- 已执行 Node.js 控制台核心测试，38 项通过。
- 未执行 JDBC/Testcontainers 测试：仓库规定必须在 Rocky 容器环境验证，但该环境当前为 JDK 17、Maven 3.6.3，未满足 JDK 21/Maven 3.9+ 前置条件。

## 全量核定记录（2026-11-13）

- 核定结论：待续。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：原自动化状态排除容器集成测试，完整容器验收未见收口。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：上述剩余事项继续由原任务/验收范围跟踪；本次不执行它们，不依据后来相关主题自动宣布原验收通过，也不补造取消或移交。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。
