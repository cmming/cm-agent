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

## 全量核定记录（2026-11-13）

- 核定结论：已归档。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：全局错误编号和诊断目标已完成，集中告警与分布式追踪属于后续范围。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：无未关闭的本包事项移交；原记录中的独立后续需求、生产使用条件及范围外限制继续保留，不作为其已实现或已验收的证明。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。
