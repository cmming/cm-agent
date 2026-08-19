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
