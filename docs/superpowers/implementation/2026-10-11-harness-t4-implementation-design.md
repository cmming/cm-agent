# T4 实际实现说明

日期：2026-10-11；主题：harness-t4；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

新增 `cm-agent-server/src/test/java/com/cmagent/server/runtime/HarnessSkillSandboxIntegrationTest.java`，实际生产 Harness、固定 CM Skill 桥接、ManagedSkillSandbox、Docker、JDBC 和文件存储配合回环模型。按系统属性选择 PostgreSQL16/MySQL8.4，只允许 Rocky 的显式 CM_AGENT_TEST_SANDBOX=true 环境执行。每库 16 项全部通过。

覆盖：版本发布后的原快照审批恢复、PENDING 隔离、READY 下载与重建、跨主体/租户拒绝、重复调用仅一次副作用、读取/准备次数及字节共用预算、执行中撤销、严格审计故障、等待停止、恢复前撤销、必需依赖授权撤销、候选 TEST、超时/输出/链接拒绝、未登记路径、密文损坏 HTTP503/安全错误编号，以及实际容器运行时停止。固定脚本同时核实 stdin、非 root 与 Docker socket 不可见；隔离、子进程销毁等由独立真实 Docker 回归补充。

生产修复仅位于 `cm-agent-server/src/main/java/com/cmagent/server/runtime/GovernedSkillAccessService.java`：在全部资源关闭后区分可信 CANCELLING + 真实线程中断 + 无附加关闭失败的正常用户停止；严格取消审计成功后抛已有停止信号，不再记录为致命超时。状态读取或审计失败转换为现有 PERSISTENCE_UNAVAILABLE/AUDIT_UNAVAILABLE，复用原 attemptId 诊断并脱敏。`GovernedSkillAccessServiceTest` 增加五项边界，普通超时和附加清理故障继续失败，严格失败优先级保持。

对象重建仅重建文件服务与 FileSystemSkillArtifactStorage，使用原 JDBC 元数据、同一私有测试卷及随机测试密钥，验证原密文不依赖对象缓存。审批恢复由生产执行器重新创建 Harness/SkillSession，仍使用旧固定快照。没有声称强杀进程恢复、容器进程外接管或恢复提交决定后的未知副作用。

未新增生产执行通道、API、配置键、迁移或依赖。其他 T2/T3 实现及历史工作包保留；测试直接插入合成技能版本资源用于锁定合同，不等同于 ZIP 上传链路验收。同步 adapter README、operations 和 release-notes；实际改动共 5 个已有文件及 7 个新文件。本地不同合同 81 项、双库 32 项及独立真实 Docker 10 项通过，命令见账本。

任务映射：S0 为当前证据核对；S1 为固定 Skill 与撤销/预算/防重；S2 为停止分类生产修复与严格失败；S3 为产物/TEST/重建；S4 为实测验收；S5 为文档与保护检查，均已完成。

关联：[清单](../checklists/2026-10-11-harness-t4-checklist.md)、[提示词](../prompts/2026-10-11-harness-t4-prompt.md)、[设计](../specs/2026-10-11-harness-t4-design.md)、[计划](../plans/2026-10-11-harness-t4.md)、[账本](../progress/2026-10-11-harness-t4-ledger.md)。
