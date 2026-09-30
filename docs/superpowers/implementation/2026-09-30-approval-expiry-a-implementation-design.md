# A 批次实际实现

关联：[设计](../specs/2026-09-30-approval-expiry-a-design.md)、[计划](../plans/2026-09-30-approval-expiry-a.md)、[账本](../progress/2026-09-30-approval-expiry-a-ledger.md)。

## 实际代码

- core `ApprovalExpiryPage` 与 `ToolApprovalRepository.findExpiredPending/expirePending` 定义有界游标和完整归属 CAS；JDBC/memory 使用一致截止边界，决定在截止时间相等时拒绝。
- V14 增加 `(status, expires_at, id)` 索引；无新增字段。JDBC 查询只发现一批，系统调度边界取仓储返回的可信 tenant。
- `ToolApprovalService` 统一会话和 TEST 过期路径。`RunRepository.expireWaitingApproval` 只更新 WAITING_APPROVAL；不调用带失败补偿的普通 complete，也不进入 Runtime。获胜后删除按 Run 原主体构造的状态槽，写系统或人工主体的严格审计。
- 独立 TEST 的 SkillTrial 同事务转 FAILED，维持不具发布资格；`SkillTrialService.finalizeResult` 不允许迟到等待结果重新打开终态。这是复核后增加的必要局部修正。
- 生产构造器注入 `SkillUnitOfWork`，使 Trial 更新遵循真实 memory 仓储的暂存写入约束；JDBC 复用外层 10 秒事务。真实 memory 仓储测试和完整应用 JVM 重启测试补足只用 Mockito 仓储时不能发现的装配问题。
- `ApprovalExpiryScanner` 一轮读取一批，游标跨轮前进；完整遍历固定截止时间，空页才开始下次遍历。失败项被跳过后在下一遍重试，持续到期的新候选不会饿死已恢复项。使用服务端 UTC 时钟；单项事务超时 10 秒，本轮 10 秒后不再开启新项。启停、间隔和批次由 `ApprovalExpiryProperties` 校验。
- TEST 控制台在 POST 失败后废弃旧操作入口并 GET 查询；新增只读刷新，终态不能提交。聊天继续使用既有权威查询与审批历史。
- 新 TEST 与权威查询开始前清除旧 PASSED 发布入口，NOT_TRIGGERED/等待态不沿用前一次按钮。`SkillReleaseController` 显式绑定 `skillId`；MockMvc 禁用参数名发现器验证发布、回滚、映射、预检及绑定不依赖编译器 `-parameters`，不修改全局编译配置。
- `JdbcRunRepository.complete` 以持久化 `startedAt` 为结束时间下限：MySQL 既有 TIMESTAMP 字段可能把毫秒开始时间舍入到下一秒，极快 Run 的原始结束时间因此早于存储值。保留领域不变量，不修改已发布迁移或数据库精度；新增双库回归。

## 验证边界

本机 Java 21 执行不依赖容器的定向测试；Rocky 执行双库与全量。隔离验证快照及最终结果见账本，原工作树配置和运行服务未动。浏览器使用 `ApprovalExpiryBrowserFixture`，端口 18093、回环地址、test profile、进程随机密钥与专用临时密码；可控 Runtime 只通过真实技能网关读取或产生授权 HIGH 工具 ASK，从不调用模型或工具执行器。fixture 启用 HTTP 定义仅用于必需依赖完整性预检，仍禁止明文 HTTP。验收脚本、截图、脱敏结果保留在 `cm-agent-server/target/`，不加入版本控制。
CodeGraph 查询 ToolApprovalService 返回 MCP Servlet 等不匹配源码，已经直接复核当前审批服务、仓储和测试；未重建索引。
