# T2 Harness 生产接入清单

日期：2026-10-10；主题：harness-t2；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

用户明确授权推进 T2，来源为[准入修复提示词](../prompts/2026-10-10-harness-entry-hardening-prompt.md)和[原架构计划](../plans/2026-10-10-harness-architecture.md)。原十八份分析、实验、准入修复文档保留历史；本包单独记录生产接入实施。

| 编号 | 状态 | 工作与验收 | 依赖 | 位置与证据 |
| --- | --- | --- | --- | --- |
| H0 | 完成 | 核实当前基线、POM、配置与来源，保护原修改 | 无 | CodeGraph、before-hashes.json；原十八份 Harness 文档及无关脏文件哈希未变 |
| H1 | 完成 | 生产拒绝型宿主能力、私有空目录、持续拒绝及清理 | H0 | AgentScopeHarnessIsolation；Windows/Linux 隔离合同各5项通过 |
| H2 | 完成 | Harness包装复用治理循环、最终白名单、审批/取消/固定Skill | H1 | HarnessExecutor、ReAct生命周期、ExecutionMode；共用30项事件合同及14项构造边界通过，Skill双策略4项通过 |
| H3 | 完成 | 同版正式依赖、模式装配、排空与旧实例拒绝 | H2 | RuntimeProperties/Configuration、模式仓储、双库V21；配置10项及冲突3项通过，依赖树核实 |
| H4 | 完成 | 当前源码本地与Rocky双库验证 | H3 | Adapter146项：145通过1外部用例跳过；Core96及Server51全过；双库5项与Linux Harness49项全过 |
| H5 | 完成 | 六份工作包、生产说明与保护检查 | H4 | README、Adapter README、configuration、operations、release-notes同步，链接/格式/差异检查通过 |

实施依赖 H0→H1→H2→H3→H4→H5。H1涉及实际生产隔离对象，H2涉及Adapter执行策略，H3涉及Core模式SPI、Server装配和Persistence V21，H4实际命令与逐项结果以账本为准。完成表示本轮代码与合同验收完成，不表示已提交或上线。

验收边界：模型只能看见当前CM治理桥接工具，默认wait_async_results已移除；宿主文件读写、后台发布/登记不开放。每次运行/恢复独占资源，审批仍使用宿主Store；终态、严格失败、中断和关闭语义通过共用合同核对。旧工厂不提供Harness JAR也能构造ReAct。

模式仅由可信部署配置决定，默认react；未知值启动失败。切换需排空非终态Run、TEST及全部检查点，包括过期/孤立记录；同模式重启允许恢复。模式变化后旧实例在凭据前受控拒绝。V21只增加系统元数据，不产生第二套长期历史。

T3–T6仍待后续授权实施：生产审批恢复和停止竞态、真实Skill沙箱/产物、完整上下文、真实模型/浏览器/回退验收。原T1完整安全准入不得用本轮局部绿色代替；没有自动生产放行。全部旧二进制需先停用，模式门禁不能约束未实现门禁的旧代码。

## 关联产物

- [清单](../checklists/2026-10-10-harness-t2-checklist.md)
- [提示词](../prompts/2026-10-10-harness-t2-prompt.md)
- [设计](../specs/2026-10-10-harness-t2-design.md)
- [计划](../plans/2026-10-10-harness-t2.md)
- [实现说明](../implementation/2026-10-10-harness-t2-implementation-design.md)
- [账本](../progress/2026-10-10-harness-t2-ledger.md)
