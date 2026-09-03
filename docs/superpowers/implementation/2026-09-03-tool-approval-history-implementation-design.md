# 会话审批历史实现说明

关联：[设计](../specs/2026-09-03-tool-approval-history-design.md)、[计划](../plans/2026-09-03-tool-approval-history.md)、[账本](../progress/2026-09-03-tool-approval-history-ledger.md)。

## 实际实现

- core 的 `ToolApprovalHistoryPageRequest` 与 `ToolApprovalRepository.listHistory` 作为共享查询合同，不依赖 Web 类型。
- `JdbcToolApprovalRepository` 读取已有审批表及明细；`InMemoryToolApprovalRepository` 使用相同过滤与排序。UUID 按字符串排序，避免 Java UUID 有符号比较与数据库 CHAR 排序不一致。
- `ToolApprovalService.listHistory` 只做查询及安全视图转换。Controller 完成 `agent:read`、会话归属、受限游标处理，满页时额外探测 1 条决定是否返回 nextCursor。
- 游标包含格式版本、Agent、会话、决定时间、审批标识；Base64 URL 编码，不包含 tenant 或检查点。tenant 始终取自认证主体，不因游标有效而放宽权限。
- `ApiExceptionHandler` 区分决定接口和历史参数错误；历史查询通过服务端 request attribute 提供可信诊断上下文，持久化失败使用 PERSISTENCE_UNAVAILABLE/503，同一 errorId 关联前端与脱敏日志。
- 前端 `mergeApprovalHistory` 按 approvalId 合并，`groupApprovalHistory` 按 Run 分组；`loadChatMessages` 同时读取消息、待审批、历史。历史失败不冒充无审批，不阻断已经成功读取的消息和待审批。
- `renderApprovalHistory` 优先匹配消息 runId，否则使用历史区；独立 `createApprovalHistoryGroup` 不复用决定表单，历史没有提交控件。`loadApprovalHistory` 保留分页失败时的记录/游标并隔离旧响应。
- v2 资源更新为 app/styles 2.0.17、console-core 2.0.11，避免旧资源缓存掩盖新页面入口。

## 兼容性与边界

原 PENDING 查询、审批决定 SSE、加密检查点与恢复行为不变。复用 V11 表，无本任务新增 Flyway 迁移或配置键。现有按 tenant/Agent/会话的索引前缀缩小查询范围；高容量会话可后续评估专用排序索引。

历史仅包含已落库终态，读取不推进 PENDING 的过期状态。memory 不是跨重启持久化。消息窗口没有匹配 Run 时使用独立区域，不扩展消息接口。按设计实现，无新增审批产品权限或代批能力。

## 验证

以[进度账本](../progress/2026-09-03-tool-approval-history-ledger.md)的实际执行结果为准。
