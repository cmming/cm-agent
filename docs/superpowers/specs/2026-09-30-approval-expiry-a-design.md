# A 批次审批主动过期设计

基线：b7c7280d4e542c565cf11c482a4c53efd1e99516。独立分支：codex/approval-expiry-a。
仅实现清单 A/T0～T8，不实现租约、自动重放、独立审批中心。历史 next-iteration-analysis 文档保留在原工作树。

## 契约与安全边界

系统扫描使用 UTC 服务端时间；截止时间相等即过期。跨租户候选发现只在服务端调度边界使用；更新取持久化候选的 tenant、Agent、scope、会话、Run、版本和截止时间。系统审计主体固定为 system:approval-expiry，无用户权限，不调用 Runtime。
候选按 expires_at、规范 UUID 字符串升序分页，每轮最多一批；一次完整遍历固定截止时间，直到空页才重置截止时间与游标，避免持续新增到期候选让遍历永不结束。游标跳过失败候选，遍历到底后从头重试，防止首项故障饿死其他租户。默认启用、间隔 30 秒、批次 50，上限 100；关闭只停止扫描，不禁止人工过期收口。

## 事务与竞争

会话和 TEST 共用过期服务。审批 CAS 同时验证 PENDING、版本、scope、资源归属和 expires_at <= 本轮截止时间。只有获胜方可收口 WAITING_APPROVAL Run、删除该 Run 状态槽及写严格审批/运行审计。Run 使用 WAITING_APPROVAL 条件更新，不能覆盖 RUNNING 或终态。JDBC 每项使用短事务（10 秒），失败全部回滚；单项异常在调度边界脱敏记录，下一轮重试。memory 仅保证单行竞争，不承诺跨仓储回滚。
两个扫描器只产生一份过期事实和审计；扫描输给决定时不收口、不清理。决定的时间条件为 expires_at > decidedAt。重启后游标归零并继续处理持久化 PENDING；已批准但结果不确定的 Run 不恢复。

## 验收

验证双 scope、截止边界、同时间游标、租户和资源错配、失败继续、诊断脱敏及竞争；Rocky PostgreSQL 16/MySQL 8.4 验证事务回滚、并发，并关闭第一个完整应用 JVM 后启动不同 PID 的扫描应用，验证无用户提交也能收口持久化审批。受控浏览器证明发布/绑定/回滚与 TEST 恢复/过期、必需依赖撤销和 NOT_TRIGGERED 门禁，不调用真实模型。
技能 Trial 更新复用现有 SkillUnitOfWork：JDBC 参与外层短事务，memory 在锁保护的暂存副本中写入，不能绕过仓储的工作单元约束。仅用于单元测试的兼容构造器不代表生产装配方式。
配套：[计划](../plans/2026-09-30-approval-expiry-a.md)、[实现](../implementation/2026-09-30-approval-expiry-a-implementation-design.md)、[账本](../progress/2026-09-30-approval-expiry-a-ledger.md)。
