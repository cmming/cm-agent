# T4 执行提示词

日期：2026-10-11；主题：harness-t4；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

用户明确授权 T4 实施，来源为 [T3 提示词](2026-10-10-harness-t3-prompt.md) 和 [架构清单](../checklists/2026-10-10-harness-architecture-checklist.md)。本文件生成与读取不自动赋予新实施或部署授权。

S0–S5 已完成。本提示词用于只读检视当前成果或用户明确授权的相关修复；未收到新实施指令时不重复执行，也不自动开展 T5/T6。

~~~text
在 F:/java/cm-agent 按当前 AGENTS.md 推进明确授权的架构 T4。mode=default，自主取舍并记录依据，不主动逐项确认。先读取本工作包六份文档：
F:/java/cm-agent/docs/superpowers/checklists/2026-10-11-harness-t4-checklist.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-11-harness-t4-prompt.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-11-harness-t4-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-11-harness-t4.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-11-harness-t4-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-11-harness-t4-ledger.md
核对 HEAD、脏文件和当前证据。存在 .codegraph 时优先定位。S0–S5 覆盖固定 Skill 快照与撤销、共享读取预算、防重、TEST、固定 Python 沙箱、加密产物、成功发布、下载归属及严格失败补偿。已完成部分依据账本检视，不机械重做；T5/T6 不自动实施。
保持 AgentScope 全部 2.0.2、默认 react/显式 harness、原审批/停止/加密状态合同。只公开 CM 受治理 Skill 桥接；run_skill_code、任意宿主路径、Shell、原生文件交付不开放。快照是审批恢复唯一来源，新发布不替换旧版本；纪元、解绑或工具授权撤销必须阻止继续，不自动重放已执行脚本。
沙箱输入只允许登记 .py 路径和有界 stdin；资源准备计入读取预算，SANDBOX_PREPARED 不等于成功。执行与 I/O 在数据库短事务外，关闭和二次授权、严格执行审计成功后才保留 PENDING；Run 成功与发布审计后才能 READY。失败、停止、撤销后的文件不可下载，补偿删除；日志和安全错误共享 errorId，不泄露正文、密钥或内部路径。
生产停止修复只在可信同一 Run/原主体已 CANCELLING、执行线程真实中断、资源关闭无附加失败时将沙箱中断视为用户停止；严格 CANCELLED 审计成功后才抛已有停止信号，仍等待实际容器与在途许可退出。普通超时、状态读取/审计和附加清理故障保持失败。真实 Harness 对同调用标识不重复沙箱副作用，直接重复网关请求仍严格拒绝，不假设所有重复模型响应必然抛同一种异常。
本地 JDK21 运行非容器合同。所有 Docker/Testcontainers/JDBC/Flyway 仅 ssh rocky、maven:3.9.9-eclipse-temurin-21、PostgreSQL16-alpine/MySQL8.4。使用新隔离目录，HEAD+当前未提交源码哈希一致；不复制真实凭据/无关 YAML、不全局清理、不修改主机时钟。实际 Harness 配回环模型不代表真实 Provider；文件服务重建不代表强杀进程。
验证 Maven 容器还需要 Rocky 的 /usr/bin/docker 只读挂载与 Docker socket，固定 Python 子沙箱不能继承宿主挂载。当前 563 份源码/SQL/POM/固定测试资源哈希一致；真实结果和逐轮失败修正以账本为准。
保护原 30 份历史工作包和无关脏文件，中文注释/JavaDoc遵守触发清单。同步本轮六文档及生产说明，按实测记录代码完成、验收结果和外部阻塞；不得提交/推送/部署。最终按变更、验证、影响、风险、直接下一步交付，并提供提示词与只读问答入口。
~~~

关联：[清单](../checklists/2026-10-11-harness-t4-checklist.md)、[设计](../specs/2026-10-11-harness-t4-design.md)、[计划](../plans/2026-10-11-harness-t4.md)、[实现](../implementation/2026-10-11-harness-t4-implementation-design.md)、[账本](../progress/2026-10-11-harness-t4-ledger.md)。
