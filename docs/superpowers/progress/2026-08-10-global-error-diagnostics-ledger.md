# 全局错误诊断进度账本

关联设计：[设计说明](../specs/2026-08-10-global-error-diagnostics-design.md)；关联计划：[实施计划](../plans/2026-08-10-global-error-diagnostics.md)；关联实现：[实现说明](../implementation/2026-08-10-global-error-diagnostics-implementation-design.md)。

| 任务 | 状态 | 实际结果 |
| --- | --- | --- |
| P0：REST 关联与前端展示 | 已完成 | 新增错误编号、响应头、脱敏日志和控制台结构化错误展示。 |
| P1：执行边界日志 | 已完成 | Agent、受治理工具、MCP 失败均输出统一上下文字段。 |
| P2：回归与文档 | 已完成 | 增加 REST、关联过滤器、控制台、工具与 MCP 的定向回归，并更新发布说明。 |

## 验证结果

- `node --test cm-agent-console/src/test/js/console-core.test.cjs`：37 项通过。
- `mvn -pl cm-agent-server -am -Dtest=ApiExceptionHandlerTest,RequestCorrelationFilterTest -Dsurefire.failIfNoSpecifiedTests=false test`：7 项通过。
- `mvn -pl cm-agent-server -am -Dtest=GovernedToolInvocationServiceTest,McpPublishedToolCatalogTest,ApiExceptionHandlerTest,RequestCorrelationFilterTest -Dsurefire.failIfNoSpecifiedTests=false test`：35 项通过。
- `mvn -q test`：本机无可用 Docker，11 个 Testcontainers 持久化测试在容器初始化阶段失败；未在本机继续作为通过依据。仓库要求容器验证在 Rocky 虚拟机执行，但当前改动未提交，无法确认远程工作区与本地一致，因此未执行远程验证。

## 遗留问题

- 尚未接入集中式日志、告警规则和分布式追踪；后续可在不改变错误编号契约的前提下接入。

## 提交信息

未提交。
