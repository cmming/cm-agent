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
