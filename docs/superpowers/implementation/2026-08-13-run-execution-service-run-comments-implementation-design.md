# RunExecutionService.run 业务流程注释实现说明

## 关联文档

- [设计说明](../specs/2026-08-13-run-execution-service-run-comments-design.md)
- [实施计划](../plans/2026-08-13-run-execution-service-run-comments.md)
- [进度账本](../progress/2026-08-13-run-execution-service-run-comments-ledger.md)

## 最终实现

本次只修改 `cm-agent-server` 模块中的 `RunExecutionService.run` 注释，不改变方法签名、依赖、分支结构和任何可执行语句。

方法级 JavaDoc 新增完整职责说明：入口权限由调用方完成，本方法负责租户内资源校验、授权工具筛选、运行记录创建、Runtime 调用、结果持久化、失败收口和响应脱敏。

方法内部按执行顺序标注以下业务阶段：

1. 在认证主体所属租户内查询 Agent，拒绝不存在或已禁用的 Agent，并校验启用的模型配置。
2. 根据 Agent 工具授权关系筛选本次 Runtime 可见工具。
3. 调用 Runtime 前先创建 `RUNNING` 记录，为结果、工具调用和诊断建立统一 `runId`。
4. 将租户、Agent、模型、主体、输入和授权工具组成完整上下文交给 Runtime。
5. 审计持久化异常发生时尽力关闭运行记录，然后保留原异常交给上层处理。
6. 数据持久化异常采用相同收口原则，避免数据库异常被受控运行异常掩盖。
7. 普通 Runtime 异常记录可关联诊断，尝试完成失败状态和失败审计，再转换为受控异常。
8. Runtime 成功返回后持久化运行终态与工具调用，并用持久化记录构造脱敏响应。

## 调用链与数据变化

调用链保持不变：`RunController` 完成认证和 `agent:run` 权限检查后调用 `RunExecutionService.run`；该方法继续通过 Repository、`RunPersistenceService` 和 `AgentRuntime` 完成原有编排。

没有新增或修改领域数据、数据库表、接口 DTO、配置项、日志字段或错误码。

## 与原方案的差异

与已确认方案无差异。实现阶段将审计异常与数据库异常分别添加说明，使两条分支的业务语义更直接，但仍属于原设计中的“区分异常收口路径”。

## 验证说明

最终验证结果记录在进度账本中。由于变更仅涉及注释，不新增测试用例；验证重点是 Java 21 编译、空白检查和确认 Java 差异只包含注释。
