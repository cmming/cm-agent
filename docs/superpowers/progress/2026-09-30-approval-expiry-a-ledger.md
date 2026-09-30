# A 批次进度与验收账本

关联：[设计](../specs/2026-09-30-approval-expiry-a-design.md)、[计划](../plans/2026-09-30-approval-expiry-a.md)、[实现](../implementation/2026-09-30-approval-expiry-a-implementation-design.md)。

| 任务 | 状态 | 证据 |
| --- | --- | --- |
| T0 | 完成 | HEAD b7c7280；已读取 AGENTS、POM、README、configuration、operations、清单；独立工作树 F:/java/cm-agent-approval-expiry-a |
| T1 | 进行中 | 仓储当前缺少跨租户有界过期发现，expire 缺截止时间条件 |
| T2 | 待做 | 会话和 TEST 现有重复过期链待统一 |
| T3 | 待做 | 缺调度和配置 |
| T4 | 待做 | 待复核权威刷新 |
| T5 | 待做 | 本机初始 Java/Maven 使用 JDK17，执行前切 JDK21 |
| T6 | 待做 | ssh rocky Docker 23.0.6 可用；/root/cm-agent 不存在，使用新的项目隔离目录 |
| T7 | 待做 | 受控 Runtime 与浏览器待验证 |
| T8 | 进行中 | 四份本轮文档已新建；未提交 |

原工作树的 application*.yml、.codex/、.workbuddy/ 和历史分析文档保持不动。不推送、不合并、不部署。
