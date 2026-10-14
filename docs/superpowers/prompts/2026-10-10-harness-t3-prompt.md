# T3 执行提示词

日期：2026-10-10；主题：harness-t3；修订：R0；模式：mode=default。
Git 基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

用户明确授权推进架构 T3；来源为 [T2 提示词](../prompts/2026-10-10-harness-t2-prompt.md) 与 [架构清单](../checklists/2026-10-10-harness-architecture-checklist.md)。T2 的只读入口不限制本轮明确实施授权。历史架构、兼容实验、入口收紧、T2 共 24 份文档保持原哈希。

本提示词用于检视当前T3与明确授权的后续修复；读取/生成本文件不自动授权实施、提交或部署。T4–T6不包含在本工作包。

~~~text
仓库 F:/java/cm-agent，mode=default，工作包 R0。先读取 AGENTS.md 及以下六份文档，核对当前 HEAD、脏文件和最新证据。自主处理已授权范围并记录依据，不主动逐步确认。用户最新指令优先。
F:/java/cm-agent/docs/superpowers/checklists/2026-10-10-harness-t3-checklist.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-10-harness-t3-prompt.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-10-harness-t3-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-10-harness-t3.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-10-harness-t3-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-10-harness-t3-ledger.md
A0–A5已完成：审批恢复、加密运行状态、停止和严格失败合同。当前入口供只读检视或明确授权的修复；先核对最新证据，不机械重做已通过验收，不自动扩展到T4–T6。所有AgentScope保持2.0.2，默认react，显式harness；MCP Server2.0.0/示例客户端0.17.0不改，无新增API、配置或迁移。
仅WAITING_APPROVAL在正常交付时保留检查点；恢复前直接读取同一可信槽，禁止读取故障回退新执行。审批创建/决定通知/恢复前置失败统一关闭活动Run和清理状态，已经终态也不跳过清理；补偿共享短锁与JDBC短事务，外部执行在事务外，清理异常保持严格优先级并保留原原因。
保持原runId、tenant:principal、原发起主体、固定Skill快照、工具ID/入参哈希和最新授权；不重复用户消息或长期历史，不接受匿名状态写入/删除。部分批准只执行允许项，逐项拒绝保留历史；模型正常完成时沿用原成功合同，区别于执行授权拒绝。无会话Run/TEST的HIGH仍直接拒绝。
实际Harness及生产治理/隔离对象配回环模型桩；审批重复与竞争只允许一次副作用。恢复停止先CANCELLING，必须等待实际在途调用退出才交付终态，严格审计失败不能降级为CANCELLED；不接纳迟到输出，不自动重放，不跨实例接管。
本地JDK21选择adapter和server非容器合同。容器、JDBC、Flyway、Testcontainers仅ssh rocky，maven:3.9.9-eclipse-temurin-21，PostgreSQL16/MySQL8.4；核对HEAD及当前未提交覆盖源码哈希，不复制部署凭据或无关YAML，不全局清理。Rocky与本机时钟偏差不擅自修改，不声称跨主机TTL验收。
所有注释、JavaDoc与文档使用中文。按实际结果同步六份产物及生产说明，记录未运行验收/阻塞与继续条件，不以回环或对象重建证明真实Provider、浏览器、强杀进程或多实例生产验收。保护全部既有脏文件与历史。最终输出变更、实际命令结果、影响、风险和直接下一步，明确未提交、未推送、未部署。
~~~

## 关联产物

- [清单](../checklists/2026-10-10-harness-t3-checklist.md)
- [设计](../specs/2026-10-10-harness-t3-design.md)
- [计划](../plans/2026-10-10-harness-t3.md)
- [实现说明](../implementation/2026-10-10-harness-t3-implementation-design.md)
- [账本](../progress/2026-10-10-harness-t3-ledger.md)

