# 工具调试错误可观测性进度账本

## 任务状态

| 任务 | 状态 | 结果 |
| --- | --- | --- |
| 失败链路检查 | 已完成 | 确认具体原因在 `ToolDebugService` 被统一文案覆盖 |
| 响应契约扩展 | 已完成 | 新增 `errorCode`、`errorId`，错误编号复用 `toolCallId` |
| 后台错误日志 | 已完成 | 结果失败记录结构化原因，异常失败记录脱敏诊断堆栈 |
| 控制台展示 | 已完成 | 状态区和详情区显示具体原因、错误码和错误编号 |
| 自动化测试 | 已完成 | 服务、控制器、静态资源和控制台脚本相关测试均已通过 |
| 文档同步 | 已完成 | 已更新开发指南、发布说明和四份过程文档 |

## 实际验证结果

- `java -version`：通过，OpenJDK 21.0.11。
- `mvn -v`：通过，Apache Maven 3.9.4 使用 Java 21.0.11。
- `node --test cm-agent-console/src/test/js/console-core.test.cjs`：通过，37 个测试全部成功。
- `mvn -pl cm-agent-server -am "-Dtest=ToolDebugServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：通过，`ToolDebugServiceTest` 12 个测试全部成功。
- `mvn -pl cm-agent-server -am "-Dtest=ToolControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dcm-agent.agentscope.enabled=false" "-Dcm-agent.fake-runtime-enabled=true" test`：通过，24 个控制器测试全部成功。
- `mvn -pl cm-agent-server -am "-Dtest=ToolDebugServiceTest,ToolControllerTest,ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：首次运行时，`ConsoleResourceTest` 9 个测试和 `ToolDebugServiceTest` 12 个测试通过；`ToolControllerTest` 因工作区现有配置同时启用真实运行时和 fake runtime 而在启动校验阶段失败，随后使用上述临时测试参数重跑并通过。未修改用户配置。
- `git diff --check`：通过，仅报告工作区现有 LF/CRLF 转换提示，没有空白错误。

首次服务端针对性测试命令因 PowerShell 拆分未加引号的 `-D` 参数而在 Maven 生命周期解析阶段失败，未进入编译或测试；修正参数引号后测试通过，该失败与代码无关。

## 遗留问题

当前没有功能性遗留问题。集中式日志采集、链路追踪和跨服务错误编号传播不在本任务范围内。

## 提交信息

未提交。

## 关联文档

- [需求设计](../specs/2026-08-10-tool-debug-error-observability-design.md)
- [实现计划](../plans/2026-08-10-tool-debug-error-observability.md)
- [实现说明](../implementation/2026-08-10-tool-debug-error-observability-implementation-design.md)

## 全量核定记录（2026-11-13）

- 核定结论：已归档。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：调试失败原因、编号与日志关联目标验证完成，集中日志与追踪明确在范围外。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：无未关闭的本包事项移交；原记录中的独立后续需求、生产使用条件及范围外限制继续保留，不作为其已实现或已验收的证明。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。
