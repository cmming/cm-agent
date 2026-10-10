# 会话停止本轮实施：实际实现

日期：2026-10-10；主题：`chat-stop-delivery`；修订：R0；模式：`mode=default`；已按用户指令纳入本地提交，未推送、未部署。原分析包是需求形成历史，本包记录实际代码。

## 实际文件与行为

| 任务 | 代码位置（相对仓库） | 实际交付 |
|---|---|---|
| T1 | cm-agent-core/.../domain/RunStatus、RunRecord、AgentRunRequest；runtime/RunCancellation、AgentRuntime；repository/* | 非终态/终态不变量，独立停止信号、原子准入及退出等待，旧构造器兼容和默认失败关闭 |
| T2 | cm-agent-agentscope-adapter/.../AgentScopeRuntimeAdapter、AgentScopeReActExecutor、AgentScopeRunGate | 取消订阅、抑制迟到事件、阻止新准入、只对用户停止等待实际调用退出，停止检查点清理，严格失败传播 |
| T3 | cm-agent-server/.../runtime/ConversationService、RunExecutionService、RunPersistenceService、ToolApprovalService、ConversationPromptComposer；store/*；cm-agent-persistence/.../Jdbc*Repository | 活跃 Run 查询、快照 CAS、短事务收口、正文保存、中断上下文、审批取消和同一 Run 恢复 |
| T4 | cm-agent-server/.../runtime/RunControlRegistry、RunControlException；web/ConversationController、ApiExceptionHandler；cm-agent-api/.../ApiErrorCode | 单实例可信句柄、固定分段锁、身份匹配释放、取消/状态 API、中文冲突与同号日志 |
| T5 | cm-agent-console/src/main/resources/META-INF/resources/assets/app.js、console-core.js、chat-stop.css、icons/*；console/v2/*.html | 独立停止/状态按钮、权威轮询、陈旧响应隔离、发送锁、失败不重试、缓存版本同步 |
| T6 | README.md、docs/configuration.md、operations.md、release-notes.md；本组六份 | 生产行为和单实例限制、发布边界、实际证据及可继续问答入口 |

`...` 为相应模块的 `src/main/java/com/cmagent` 包路径。新增测试位于各模块 src/test，包括 RunCancellationTest、RunRecordStopTest、RunControlRegistryTest、ChatStopFlowTest、AgentScopeExternalStopTest、chat-stop.test.cjs；原审批、控制器、JDBC 与资源测试同步更新。

## 关键调用链

ConversationService.send → 提交 Run/USER → 注册句柄 → RunExecutionService.runPreparedAndFinalize → Adapter.runStructured → RunGate 许可 → 最终短锁/事务保存 Run + assistant/PENDING → 在短锁内释放句柄。

cancel → 验证可信归属/发起人/权限 → CAS + 严格审计 → 信号 request → 取消模型订阅并阻止新网关调用 → awaitQuiescence → 短事务 CANCELLED；等待审批无活跃片段时直接取消审批、删除检查点并完成。

decideAndResume → 验证版本、Run 等待状态 → 决定/审计/Run 恢复 RUNNING 短事务 → 注册新片段句柄 → 原 Run 恢复 → 同一 finalizer。上一片段 finally 使用 `remove(runId, expectedExecution)`，防止删除新片段。

前端从 started 或审批决定取得原 runId，通过 GET active-run 与单 Run 状态恢复；POST 不使用 AbortController，不自动重试。消息页返回仅关联 Run 的 runStatuses，为持久化消息添加已停止提示。

## 相对设计的明确调整

- 无需迁移或新配置：现有 Run 字符串状态字段可以保存新值，双库验证通过。
- AgentScope 通用中断不足以证明结束，因此增加实际网关许可；旧超时路径仍保留原合同，只在用户停止时等待退出。
- 启动和批准恢复增加完整活跃 Run 锁；批准后同一 ID 转 RUNNING，修复刷新误认待审批。
- 审批等待与下一执行片段存在快速衔接，增加句柄身份释放，避免迟到 finally 的 ABA 式清理。
- 前端图标采用 vendored Lucide 0.468 的 square/refresh-cw 和 ISC 许可，无新运行依赖。
- 独立外部测试只从已授权环境读取凭据，两轮无工具；浏览器仍使用受控 fixture，证据类型明确分开。

## 交付边界

没有更改用户的 SkillArtifactProtocol、application 配置、index.html、.codex/.workbuddy；没有 schema 迁移、依赖升级、推送或部署。本地提交仅包含本需求相关代码、测试、说明和两组工作包。已取消 Run 仍不能绕过“成功后文件交付”门禁。不响应中断的外部调用等待、服务重启孤立 Run 和跨实例句柄仍是已说明的限制，不自动补偿。


## 关联产物

- [清单](../checklists/2026-10-10-chat-stop-delivery-checklist.md)
- [提示词](../prompts/2026-10-10-chat-stop-delivery-prompt.md)
- [设计](../specs/2026-10-10-chat-stop-delivery-design.md)
- [计划](../plans/2026-10-10-chat-stop-delivery.md)
- [账本](../progress/2026-10-10-chat-stop-delivery-ledger.md)
