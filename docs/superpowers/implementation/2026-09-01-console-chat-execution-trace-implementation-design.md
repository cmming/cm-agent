# 会话聊天执行过程展示实现说明

## 关联文档

- [设计说明](../specs/2026-09-01-console-chat-execution-trace-design.md)
- [实施计划](../plans/2026-09-01-console-chat-execution-trace.md)
- [进度账本](../progress/2026-09-01-console-chat-execution-trace-ledger.md)

## 当前状态

设计基线已确定，尚未进入代码开发。本文件将在实现完成后补充最终代码位置、事件调用链、持久化行为、
前端交互、测试证据以及与原设计的差异。

## 计划实现基线

- Core 增加 `THINKING` 内容块和受控执行进度事件，不修改既有运行结果 JSON。
- AgentScope adapter 只把完整 thinking 块和无载荷的工具生命周期事件交给上层。
- server 在可信运行边界脱敏进度内容，并通过新增 `progress` SSE 事件传递。
- console 在 assistant 消息内以可折叠轨迹展示 thinking、工具阶段和最终受控摘要。
- 消息仍保存到既有 `content_blocks_json`，不新增数据库结构。

## 与设计的差异

当前无差异；开发完成后据实更新。
