# A 批次实际实现

关联：[设计](../specs/2026-09-30-approval-expiry-a-design.md)、[计划](../plans/2026-09-30-approval-expiry-a.md)、[账本](../progress/2026-09-30-approval-expiry-a-ledger.md)。

## 实际代码

- core `ApprovalExpiryPage` 与 `ToolApprovalRepository.findExpiredPending/expirePending` 定义有界游标和完整归属 CAS；JDBC/memory 使用一致截止边界，决定在截止时间相等时拒绝。
- V14 增加 `(status, expires_at, id)` 索引；无新增字段。JDBC 查询只发现一批，系统调度边界取仓储返回的可信 tenant。
- `ToolApprovalService` 统一会话和 TEST 过期路径。`RunRepository.expireWaitingApproval` 只更新 WAITING_APPROVAL；不调用带失败补偿的普通 complete，也不进入 Runtime。获胜后删除按 Run 原主体构造的状态槽，写系统或人工主体的严格审计。
- 独立 TEST 的 SkillTrial 同事务转 FAILED，维持不具发布资格；`SkillTrialService.finalizeResult` 不允许迟到等待结果重新打开终态。这是复核后增加的必要局部修正。
- `ApprovalExpiryScanner` 一轮读取一批，游标跨轮前进；失败项被跳过后在下一遍重试，避免长期占据首批。使用服务端 UTC 时钟；单项事务超时 10 秒，本轮 10 秒后不再开启新项。启停、间隔和批次由 `ApprovalExpiryProperties` 校验。
- TEST 控制台在 POST 失败后废弃旧操作入口并 GET 查询；新增只读刷新，终态不能提交。聊天继续使用既有权威查询与审批历史。

## 验证边界

本机 Java 21 执行不依赖容器的定向测试；Rocky 执行双库与全量。隔离验证提交 eb38920，原工作树配置和运行服务未动。浏览器和新版最终回归证据持续记录到账本；受控 Runtime 不等于真实模型验收。
CodeGraph 查询 ToolApprovalService 返回 MCP Servlet 等不匹配源码，已经直接复核当前审批服务、仓储和测试；未重建索引。
